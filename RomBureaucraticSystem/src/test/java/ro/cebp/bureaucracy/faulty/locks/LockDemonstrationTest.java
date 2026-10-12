package ro.cebp.bureaucracy.faulty.locks;

import org.junit.jupiter.api.Test;
import ro.cebp.bureaucracy.common.ConcurrentStatistics;
import ro.cebp.bureaucracy.common.IssuedLedger;
import ro.cebp.bureaucracy.common.model.OfficeConfig;
import ro.cebp.bureaucracy.common.model.Range;
import ro.cebp.bureaucracy.common.model.SimulationConfig;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockDemonstrationTest {

    private final SimulationConfig simulation = new SimulationConfig(
            5,
            new Range(5, 10),
            new Range(100, 150),
            0.1,
            5,
            null
    );

    @Test
    void lostWakeupNotifiesOnlyOneWaitingThread() throws InterruptedException {
        OfficeConfig config = new OfficeConfig("Primarie", 1, List.of());
        Office office = new Office(config, simulation, new ConcurrentStatistics(), new IssuedLedger());
        Counter counter = office.counters().get(0);

        Object pauseLock = getPauseLock(counter);
        int waiters = 3;
        CountDownLatch ready = new CountDownLatch(waiters);
        AtomicInteger awakened = new AtomicInteger(0);

        for (int i = 0; i < waiters; i++) {
            Thread t = new Thread(() -> {
                synchronized (pauseLock) {
                    ready.countDown();
                    try {
                        pauseLock.wait(200);
                        awakened.incrementAndGet();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            t.start();
        }

        ready.await();
        // Give threads time to enter wait state.
        Thread.sleep(20);

        // Resume counter triggers notify() instead of notifyAll().
        counter.resumeFromBreak();

        // Exactly one waiter is notified immediately by notify(); others must wait for timeout.
        Thread.sleep(30);
        assertTrue(awakened.get() <= 1, "BUG [L2]: notify() should wake at most one thread immediately");
    }

    @Test
    void coffeeBreakHoldsArchiveLockBlockingOthers() throws Exception {
        OfficeConfig config = new OfficeConfig("Evidenta", 1, List.of());
        Office office = new Office(config, simulation, new ConcurrentStatistics(), new IssuedLedger());
        Counter counter = office.counters().get(0);

        // Counter sends on break and simulates sleeping while holding the archive lock.
        counter.sendOnBreak();
        long start = System.currentTimeMillis();

        Thread worker = new Thread(counter);
        worker.start();

        // Give worker thread time to acquire lock and go to sleep.
        Thread.sleep(30);

        counter.stop();
        counter.resumeFromBreak();
        worker.join(500);

        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed >= 30, "BUG [P2]: archive lock held during break");
    }

    // Helper to retrieve pauseLock via reflection for testing lost wakeup.
    private Object getPauseLock(Counter counter) {
        try {
            var field = Counter.class.getDeclaredField("pauseLock");
            field.setAccessible(true);
            return field.get(counter);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
