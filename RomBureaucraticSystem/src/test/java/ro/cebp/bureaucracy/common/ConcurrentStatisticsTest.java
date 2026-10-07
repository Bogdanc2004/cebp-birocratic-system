package ro.cebp.bureaucracy.common;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConcurrentStatisticsTest {

    private static final int THREADS = 100;
    private static final int UPDATES_PER_THREAD = 10_000;

    @Test
    void noUpdateIsLostUnderContention() throws InterruptedException {
        ConcurrentStatistics stats = new ConcurrentStatistics();
        CountDownLatch go = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int t = 0; t < THREADS; t++) {
                String office = "Office-" + (t % 3);
                int id = t;
                pool.submit(() -> {
                    go.await(); // release every thread at once for maximum contention
                    for (int i = 0; i < UPDATES_PER_THREAD; i++) {
                        stats.recordIssued(office);
                        stats.recordWait(id);
                    }
                    stats.clientFinished();
                    return null;
                });
            }
            go.countDown();
        } // close() waits for all tasks

        StatisticsSnapshot snapshot = stats.snapshot();
        long expected = (long) THREADS * UPDATES_PER_THREAD;
        assertEquals(expected, snapshot.totalIssued());
        assertEquals(expected, snapshot.waitSamples());
        assertEquals(THREADS, snapshot.finishedClients());
        assertEquals(THREADS - 1, snapshot.maxWaitMs());
        assertEquals(3, snapshot.issuedPerOffice().size());
    }
}
