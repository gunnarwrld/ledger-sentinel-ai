package io.paymentslab.support

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs workers released at the same moment by a latch. */
final class Concurrency {

    private Concurrency() {}

    static final class Outcome {
        /** One entry per worker that finished, in worker order. */
        final List<Object> results = []
        /** Whatever the other workers threw. */
        final List<Throwable> failures = []
    }

    /** The closure gets the worker number (0-based). */
    static Outcome run(int workers, Closure work) {
        def start = new CountDownLatch(1)
        def pool = Executors.newFixedThreadPool(workers)
        try {
            def futures = (0..<workers).collect { int worker ->
                pool.submit({ start.await(); work(worker) } as Callable)
            }
            start.countDown()
            def outcome = new Outcome()
            futures.each { future ->
                try {
                    outcome.results << future.get(2, TimeUnit.MINUTES)
                } catch (ExecutionException e) {
                    outcome.failures << e.cause
                }
            }
            outcome
        } finally {
            pool.shutdownNow()
        }
    }
}
