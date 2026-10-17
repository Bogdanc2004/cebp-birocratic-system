package ro.cebp.bureaucracy.faulty.locks;

import org.junit.jupiter.api.Test;
import ro.cebp.bureaucracy.common.model.ResourceConfig;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PoliteLivelockPoolTest {
    @Test
    void opposingPoliteCountersStayRunnableWithoutMakingProgress() throws Exception {
        PoliteLivelockPool pool = new PoliteLivelockPool(List.of(
                new ResourceConfig("Registru", 1), new ResourceConfig("Stampila", 1)));
        AtomicInteger acquisitions = new AtomicInteger();
        Thread primarie = counter("Primarie-counter-test", pool,
                List.of("Registru", "Stampila"), acquisitions);
        Thread anaf = counter("ANAF-counter-test", pool,
                List.of("Stampila", "Registru"), acquisitions);
        try {
            primarie.start();
            anaf.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!pool.hasRunnableCycle() && System.nanoTime() < deadline) Thread.sleep(5);

            assertTrue(pool.hasRunnableCycle(), "both resource counters should keep retrying in RUNNABLE state");
            assertEquals(Thread.State.RUNNABLE, primarie.getState());
            assertEquals(Thread.State.RUNNABLE, anaf.getState());
            var bean = ManagementFactory.getThreadMXBean();
            for (int sample = 0; sample < 20; sample++) {
                long[] deadlocked = bean.findDeadlockedThreads();
                assertTrue(deadlocked == null || (java.util.Arrays.stream(deadlocked)
                                .noneMatch(id -> id == primarie.threadId() || id == anaf.threadId())),
                        "the polite retry counters must not appear in a deadlock cycle");
                Thread.sleep(10);
            }
            assertEquals(0, acquisitions.get(), "neither counter should acquire the complete resource set");
        } finally {
            primarie.interrupt();
            anaf.interrupt();
            primarie.join(500);
            anaf.join(500);
        }
    }

    private static Thread counter(String name, PoliteLivelockPool pool, List<String> uses,
                                  AtomicInteger acquisitions) {
        return Thread.ofPlatform().daemon().name(name).unstarted(() -> {
            try {
                pool.acquire(uses);
                acquisitions.incrementAndGet();
                pool.release(uses);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
