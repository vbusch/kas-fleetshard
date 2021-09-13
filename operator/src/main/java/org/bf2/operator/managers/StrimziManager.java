package org.bf2.operator.managers;

import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.internal.readiness.Readiness;
import io.quarkus.runtime.Startup;
import io.strimzi.api.kafka.model.Kafka;
import org.bf2.common.ManagedKafkaAgentResourceClient;
import org.bf2.common.ResourceInformer;
import org.bf2.common.ResourceInformerFactory;
import org.bf2.operator.operands.AbstractKafkaCluster;
import org.bf2.operator.resources.v1alpha1.ManagedKafka;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaAgent;
import org.bf2.operator.resources.v1alpha1.ManagedKafkaCondition;
import org.bf2.operator.resources.v1alpha1.StrimziVersionStatus;
import org.bf2.operator.resources.v1alpha1.StrimziVersionStatusBuilder;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Startup
@ApplicationScoped
public class StrimziManager {

    public static final String STRIMZI_PAUSE_RECONCILE_ANNOTATION = "strimzi.io/pause-reconciliation";
    public static final String STRIMZI_PAUSE_REASON_ANNOTATION = "managedkafka.bf2.org/pause-reason";

    @Inject
    Logger log;

    @Inject
    KubernetesClient kubernetesClient;

    @Inject
    ManagedKafkaAgentResourceClient agentClient;

    @Inject
    protected InformerManager informerManager;

    @Inject
    ResourceInformerFactory resourceInformerFactory;

    private Map<String, StrimziVersionStatus> strimziVersions = new ConcurrentHashMap<>();

    // this configuration needs to match with the STRIMZI_CUSTOM_RESOURCE_SELECTOR env var in the Strimzi Deployment(s)
    @ConfigProperty(name = "strimzi.version.label", defaultValue = "managedkafka.bf2.org/strimziVersion")
    protected String versionLabel;

    private volatile ResourceInformer<Deployment> strimziDeploymentsInformer;

    private ResourceInformer<Deployment> createDeploymentInformer(String namespace) {

        // this informer will get events for Deployments in the Strimzi operator namespace but
        // it skips the drain cleaner ones.
        // This is still a workaround related to the need for Deployments (in CSV) labeling in OSD 4.8
        return this.resourceInformerFactory.create(Deployment.class,
                this.kubernetesClient.apps().deployments().inNamespace(namespace),
                new ResourceEventHandler<Deployment>() {
                    @Override
                    public void onAdd(Deployment deployment) {
                        if (isStrimziDeployment(deployment)) {
                            log.debugf("Add event received for Deployment %s/%s",
                                    deployment.getMetadata().getNamespace(), deployment.getMetadata().getName());
                            updateStrimziVersion(deployment);
                            updateStatus();
                        }
                    }

                    @Override
                    public void onUpdate(Deployment oldDeployment, Deployment newDeployment) {
                        if (isStrimziDeployment(newDeployment)) {
                            log.debugf("Update event received for Deployment %s/%s",
                                    newDeployment.getMetadata().getNamespace(), newDeployment.getMetadata().getName());
                            if (Readiness.isDeploymentReady(newDeployment) ^ Readiness.isDeploymentReady(oldDeployment)) {
                                updateStrimziVersion(newDeployment);
                                updateStatus();
                            }
                        }
                    }

                    @Override
                    public void onDelete(Deployment deployment, boolean deletedFinalStateUnknown) {
                        if (isStrimziDeployment(deployment)) {
                            log.debugf("Delete event received for Deployment %s/%s",
                                    deployment.getMetadata().getNamespace(), deployment.getMetadata().getName());
                            deleteStrimziVersion(deployment);
                            updateStatus();
                        }
                    }

                    private void updateStatus() {
                        ManagedKafkaAgent resource = agentClient.getByName(agentClient.getNamespace(), ManagedKafkaAgentResourceClient.RESOURCE_NAME);
                        if (resource != null && resource.getStatus() != null) {
                            log.debugf("Updating Strimzi versions %s", getStrimziVersions());
                            resource.getStatus().setStrimzi(getStrimziVersions());
                            agentClient.updateStatus(resource);
                        }
                        // create the Kafka informer only when a Strimzi bundle is installed (aka at least one available version)
                        if (!getStrimziVersions().isEmpty()) {
                            informerManager.createKafkaInformer();
                        }
                    }

                    private boolean isStrimziDeployment(Deployment deployment) {
                        return deployment.getMetadata().getName().startsWith("strimzi-cluster-operator");
                    }
                });
    }

    @PostConstruct
    protected void onStart() {

        // checking the ReplicaSet instead of Deployments is a temporary workaround waiting for OpenShift 4.8 (with OLM operator 1.18.0)
        // OLM operator 1.17.0 (on OpenShift 4.7) doesn't support feature to set labels on Deployments from inside the CSV
        // OLM operator 1.18.0 (on OpenShift 4.8) support the above feature
        this.resourceInformerFactory.create(ReplicaSet.class,
                this.kubernetesClient.apps().replicaSets().inAnyNamespace().withLabels(Map.of("app.kubernetes.io/part-of", "managed-kafka")),
                new ResourceEventHandler<ReplicaSet>() {
                    @Override
                    public void onAdd(ReplicaSet replicaSet) {
                        log.debugf("Add event received for ReplicaSet %s/%s",
                                replicaSet.getMetadata().getNamespace(), replicaSet.getMetadata().getName());
                        // as the first Strimzi related ReplicaSets is added, create an informer for the Strimzi Deployments to watch
                        if (strimziDeploymentsInformer == null) {
                            Deployment deployment = getDeployment(replicaSet);
                            log.infof("Creating informer for Strimzi operator Deployments in %s namespace", deployment.getMetadata().getNamespace());
                            strimziDeploymentsInformer = createDeploymentInformer(deployment.getMetadata().getNamespace());
                        }
                    }

                    @Override
                    public void onUpdate(ReplicaSet oldReplicaSet, ReplicaSet newReplicaSet) {
                        // nothing to do
                    }

                    @Override
                    public void onDelete(ReplicaSet replicaSet, boolean deletedFinalStateUnknown) {
                        // nothing to do
                    }

                    private Deployment getDeployment(ReplicaSet replicaSet) {
                        // we need corresponding Deployment which takes into account if a new ReplicaSet for a common Strimzi operator
                        // was created and another one is going to be removed, in order to have the overall readiness
                        return kubernetesClient.apps()
                                .deployments()
                                .inNamespace(replicaSet.getMetadata().getNamespace())
                                .withName(replicaSet.getMetadata().getOwnerReferences().get(0).getName())
                                .get();
                    }
                });
    }

    /* test */ public void updateStrimziVersion(Deployment deployment) {
        this.strimziVersions.put(deployment.getMetadata().getName(),
                new StrimziVersionStatusBuilder()
                        .withVersion(deployment.getMetadata().getName())
                        .withReady(Readiness.isDeploymentReady(deployment))
                        .build());
    }

    /* test */ public void deleteStrimziVersion(Deployment deployment) {
        this.strimziVersions.remove(deployment.getMetadata().getName());
    }

    /**
     * Handle the Kafka pause/unpause reconciliation mechanism by adding the corresponding annotation on the Kafka custom resource
     *
     * @param managedKafka ManagedKafka instance
     * @param kafkaCluster KafkaCluster instance on which fleetshard reconcile is operating
     * @param annotations Kafka custom resource annotations on which adding/removing the pause
     */
    public void togglePauseReconciliation(ManagedKafka managedKafka, AbstractKafkaCluster kafkaCluster, Map<String, String> annotations) {
        // a Strimzi version change was asked via the ManagedKafka resource
        if (this.hasStrimziChanged(managedKafka)) {
            log.infof("Strimzi change from %s to %s",
                    this.currentStrimziVersion(managedKafka), managedKafka.getSpec().getVersions().getStrimzi());
            // Kafka cluster is running and ready --> pause reconcile
            if (kafkaCluster.isReadyNotUpdating(managedKafka)) {
                pauseReconcile(managedKafka, annotations);
                annotations.put(STRIMZI_PAUSE_REASON_ANNOTATION, ManagedKafkaCondition.Reason.StrimziUpdating.name().toLowerCase());
            // Kafka cluster reconcile is paused because of Strimzi updating --> unpause to restart reconcile
            } else if (kafkaCluster.isReconciliationPaused(managedKafka) && isPauseReasonStrimziUpdate(annotations)) {
                unpauseReconcile(managedKafka, annotations);
            }
        } else {
            // Strimzi version is consistent, Kafka is running and ready --> remove pausing reason for Strimzi updating
            if (kafkaCluster.isReadyNotUpdating(managedKafka)) {
                if (isPauseReasonStrimziUpdate(annotations)) {
                    annotations.remove(STRIMZI_PAUSE_REASON_ANNOTATION);
                }
            }
        }
    }

    /**
     * Handle the handover of the Kafka custom resource from a Strimzi operator version to another
     * by changing the corresponding selector label value
     *
     * @param managedKafka ManagedKafka instance
     * @param kafkaCluster KafkaCluster instance on which fleetshard reconcile is operating
     * @param labels Kafka custom resource annotations on which adding the selector label value
     */
    public void changeStrimziVersion(ManagedKafka managedKafka, AbstractKafkaCluster kafkaCluster, Map<String, String> labels) {
        String kafkaStrimziVersion = this.currentStrimziVersion(managedKafka);

        // Kafka cluster reconcile is paused, a Strimzi change was asked via the ManagedKafka resource --> apply version from spec to handover
        if (kafkaCluster.isReconciliationPaused(managedKafka) && this.hasStrimziChanged(managedKafka)) {
            labels.put(this.versionLabel, managedKafka.getSpec().getVersions().getStrimzi());
        // any other state always get Strimzi version from Kafka custom resource label
        } else {
            labels.put(this.versionLabel, kafkaStrimziVersion);
        }
    }

    /**
     * Compare current Strimzi version from the Kafka custom resource with the requested one in the ManagedKafka spec
     * in order to return if a version change happened
     *
     * @param managedKafka ManagedKafka instance
     * @return if a Strimzi version change was requested
     */
    private boolean hasStrimziChanged(ManagedKafka managedKafka) {
        return !this.currentStrimziVersion(managedKafka).equals(managedKafka.getSpec().getVersions().getStrimzi());
    }

    /**
     * Returns the current Strimzi version for the Kafka instance
     * It comes directly from the Kafka custom resource label or from the ManagedKafka in case of creation
     *
     * @param managedKafka ManagedKafka instance
     * @return current Strimzi version for the Kafka instance
     */
    private String currentStrimziVersion(ManagedKafka managedKafka) {
        Kafka kafka = cachedKafka(managedKafka);
        // on first time Kafka resource creation, we take the Strimzi version from the ManagedKafka resource spec
        String kafkaStrimziVersion = kafka != null && kafka.getMetadata().getLabels() != null && kafka.getMetadata().getLabels().containsKey(this.versionLabel) ?
                kafka.getMetadata().getLabels().get(this.versionLabel) :
                managedKafka.getSpec().getVersions().getStrimzi();
        return kafkaStrimziVersion;
    }

    /**
     * Pause reconcile of the Kafka custom resource corresponding to the ManagedKafka one
     * by adding the pause-reconciliation annotation on the provided annotations list
     *
     * @param managedKafka ManagedKafka instance
     * @param annotations Kafka custom resource annotations on which adding the pause
     */
    private void pauseReconcile(ManagedKafka managedKafka, Map<String, String> annotations) {
        if (!annotations.containsKey(STRIMZI_PAUSE_RECONCILE_ANNOTATION)) {
            log.debugf("Pause reconcile for %s", managedKafka.getMetadata().getName());
            annotations.put(STRIMZI_PAUSE_RECONCILE_ANNOTATION, "true");
        }
    }

    /**
     * Unpause reconcile of the Kafka custom resource corresponding to the ManagedKafka one
     * by removing the pause-reconciliation annotation from the provided annotations list
     *
     * @param managedKafka ManagedKafka instance
     * @param annotations Kafka custom resource annotations from which removing the pause
     */
    private void unpauseReconcile(ManagedKafka managedKafka, Map<String, String> annotations) {
        if (annotations.containsKey(STRIMZI_PAUSE_RECONCILE_ANNOTATION)) {
            log.debugf("Unpause reconcile for %s", managedKafka.getMetadata().getName());
            annotations.remove(STRIMZI_PAUSE_RECONCILE_ANNOTATION);
        }
    }

    /**
     * Check if Kafka reconcile is paused due to Strimzi updating request
     *
     * @param annotations Kafka custom resource annotations from which checking the pause reason
     * @return if pausing is due to Strimzi updating
     */
    private boolean isPauseReasonStrimziUpdate(Map<String, String> annotations) {
        return annotations.containsKey(STRIMZI_PAUSE_REASON_ANNOTATION) &&
                annotations.get(STRIMZI_PAUSE_REASON_ANNOTATION).equals(ManagedKafkaCondition.Reason.StrimziUpdating.name().toLowerCase());
    }

    private Kafka cachedKafka(ManagedKafka managedKafka) {
        return this.informerManager.getLocalKafka(AbstractKafkaCluster.kafkaClusterNamespace(managedKafka), AbstractKafkaCluster.kafkaClusterName(managedKafka));
    }

    /**
     * @return list of installed Strimzi versions with related readiness status
     */
    public List<StrimziVersionStatus> getStrimziVersions() {
        log.debugf("Strimzi versions %s", this.strimziVersions.values());
        return new ArrayList<>(this.strimziVersions.values());
    }

    /* test */ public void clearStrimziVersions() {
        this.strimziVersions.clear();
    }

    public String getVersionLabel() {
        return versionLabel;
    }
}