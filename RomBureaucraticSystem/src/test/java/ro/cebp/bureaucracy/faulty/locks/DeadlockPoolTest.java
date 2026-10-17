package ro.cebp.bureaucracy.faulty.locks;

import org.junit.jupiter.api.Test;
import ro.cebp.bureaucracy.common.model.ResourceConfig;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DeadlockPoolTest {
    @Test
    void watchdogDetectsCountersHoldingOppositeResources() throws Exception {
        try (DeadlockPool pool = new DeadlockPool(List.of(
                new ResourceConfig("Registru", 1), new ResourceConfig("Stampila", 1)))) {
            CountDownLatch eachHasFirstResource = new CountDownLatch(2);
            Thread primarie = Thread.ofPlatform().daemon().name("Primarie-counter-test").start(() -> {
                pool.acquire(List.of("Registru"));
                eachHasFirstResource.countDown();
                awaitOtherThenAcquire(pool, eachHasFirstResource, List.of("Stampila"));
            });
            Thread anaf = Thread.ofPlatform().daemon().name("ANAF-counter-test").start(() -> {
                pool.acquire(List.of("Stampila"));
                eachHasFirstResource.countDown();
                awaitOtherThenAcquire(pool, eachHasFirstResource, List.of("Registru"));
            });

            assertTrue(eachHasFirstResource.await(1, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (!pool.detected() && System.nanoTime() < deadline) Thread.sleep(25);
            assertTrue(pool.detected(), "watchdog should find the lock cycle within four seconds");
            assertTrue(primarie.isAlive() && anaf.isAlive(), "both counters remain blocked by the cycle");
        }
    }

    private static void awaitOtherThenAcquire(DeadlockPool pool, CountDownLatch ready, List<String> resource) {
        try {
            ready.await();
            pool.acquire(resource);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
