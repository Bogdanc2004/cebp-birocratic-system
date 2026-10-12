package ro.cebp.bureaucracy.faulty.races;

import org.junit.jupiter.api.Test;
import ro.cebp.bureaucracy.common.StatisticsSnapshot;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * These tests pass when the bug shows up: they prove that the faulty classes really are
 * broken. The artificial pauses in those classes make the races show up almost every time.
 */
class RaceDemonstrationTest {

    private static final int THREADS = 32;

    @Test
    void unsafeStatisticsLoseUpdates() throws InterruptedException {
        UnsafeStatistics stats = new UnsafeStatistics();
        int perThread = 20;
        CountDownLatch go = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int t = 0; t < THREADS; t++) {
                pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < perThread; i++) {
                        stats.recordIssued("P");
                    }
                    stats.clientFinished();
                    return null;
                });
            }
            go.countDown();
        }

        StatisticsSnapshot snapshot = stats.snapshot();
        assertTrue(snapshot.totalIssued() < (long) THREADS * perThread,
                "expected lost updates [R2], got " + snapshot.totalIssued());
        assertTrue(snapshot.finishedClients() < THREADS,
                "expected lost updates [R2], got " + snapshot.finishedClients());
    }

    @Test
    void unsafeSingletonCreatesSeveralInstances() throws InterruptedException {
        OfficeRegistry.reset();
        Set<OfficeRegistry> instances = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<>()));
        CountDownLatch go = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int t = 0; t < THREADS; t++) {
                pool.submit(() -> {
                    go.await();
                    instances.add(OfficeRegistry.getInstance());
                    return null;
                });
            }
            go.countDown();
        }
        OfficeRegistry.reset();

        assertTrue(instances.size() > 1, "expected several registries [R6], got " + instances.size());
    }

    @Test
    void unsafeCounterProducesDuplicateSerialNumbers() throws InterruptedException {
        Counter.resetSerial();
        Set<Long> serials = Collections.synchronizedSet(new HashSet<>());
        int perThread = 10;
        CountDownLatch go = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int t = 0; t < THREADS; t++) {
                pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < perThread; i++) {
                        serials.add(Counter.generateSerial());
                    }
                    return null;
                });
            }
            go.countDown();
        }

        int totalExpected = THREADS * perThread;
        // With concurrent read-modify-write on nextSerial, duplicate serials occur.
        assertTrue(serials.size() < totalExpected, "expected duplicate serial numbers [R3], got " + serials.size());
    }
}
