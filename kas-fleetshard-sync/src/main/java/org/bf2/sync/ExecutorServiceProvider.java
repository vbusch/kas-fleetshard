package org.bf2.sync;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;

import org.jboss.logging.Logger;

@ApplicationScoped
public class ExecutorServiceProvider {

    @Inject
    Logger log;

    // the number of threads should be less than the size of the rest connection pool (50 by default)
    ThreadPoolExecutor executor = new ThreadPoolExecutor(5, 10, 1, TimeUnit.MINUTES, new ArrayBlockingQueue<>(10000), new ThreadPoolExecutor.DiscardOldestPolicy() {
        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
            log.warn("Queue is full - purging an old task");
            super.rejectedExecution(r, e);
        }

    });

    @Produces
    public ExecutorService executorService() {
        return executor;
    }

}