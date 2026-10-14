package ro.cebp.bureaucracy.correct;

import org.junit.jupiter.api.Test;
import ro.cebp.bureaucracy.common.ConcurrentStatistics;
import ro.cebp.bureaucracy.common.IssuedLedger;
import ro.cebp.bureaucracy.common.model.Document;
import ro.cebp.bureaucracy.common.model.DocumentType;
import ro.cebp.bureaucracy.common.model.OfficeConfig;
import ro.cebp.bureaucracy.common.model.Range;
import ro.cebp.bureaucracy.common.model.SimulationConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoffeeBreakTest {

    private static final DocumentType DOC = new DocumentType("CertificatNastere", "Primarie", List.of());

    private static SimulationConfig simulation(double breakChance) {
        return new SimulationConfig(10, new Range(1, 5), new Range(30, 80), breakChance, 10, null);
    }

    private static Office office(int counters, double breakChance) {
        return new Office(new OfficeConfig("Primarie", counters, List.of(DOC.name())), simulation(breakChance),
                new ConcurrentStatistics(), new IssuedLedger());
    }

    private static List<CompletableFuture<Document>> submit(Office office, int count) {
        List<CompletableFuture<Document>> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CompletableFuture<Document> result = new CompletableFuture<>();
            office.submit(new Request(i, DOC, List.of(), result));
            results.add(result);
        }
        return results;
    }

    private static void awaitAll(List<CompletableFuture<Document>> results) throws Exception {
        CompletableFuture.allOf(results.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
    }

    @Test
    void officeNeverClosesCompletelyEvenUnderConcurrentRequests() throws InterruptedException {
        Office office = office(3, 0);
        AtomicInteger granted = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);

        // 32 threads all try to send every counter on break at the same moment.
        try (ExecutorService pool = Executors.newFixedThreadPool(32)) {
            for (int t = 0; t < 32; t++) {
                pool.submit(() -> {
                    go.await();
                    for (Counter counter : office.counters()) {
                        if (office.startBreak(counter)) {
                            granted.incrementAndGet();
                        }
                    }
                    return null;
                });
            }
            go.countDown();
        }

        assertEquals(2, granted.get(), "exactly 2 of 3 counters may leave");
        assertEquals(1, office.openCounters());
        assertEquals(1, office.minOpenCounters());

        office.counters().forEach(office::endBreak);
        office.counters().forEach(office::endBreak); // a second call does nothing
        assertEquals(3, office.openCounters());
    }

    @Test
    void counterOnBreakServesNobodyAndNoClientIsLost() throws Exception {
        Office office = office(2, 0);
        Counter onBreak = office.counters().get(0);
        office.start();
        try {
            // Both counters are idle in take() when the break starts.
            assertTrue(office.startBreak(onBreak));
            List<CompletableFuture<Document>> results = submit(office, 30);

            awaitAll(results);
            assertEquals(0, onBreak.served(), "a counter on break must hand clients back, not serve them");
            assertEquals(30, office.counters().get(1).served());

            office.endBreak(onBreak);
            assertFalse(onBreak.isOnBreak());
        } finally {
            office.shutdown();
        }
    }

    @Test
    void schedulerTakesBreaksAndEveryClientIsStillServed() throws Exception {
        Office office = office(3, 0.5); // a break is likely at every tick
        CoffeeBreakScheduler breaks = new CoffeeBreakScheduler(List.of(office), simulation(0.5));
        office.start();
        breaks.start();
        try {
            List<CompletableFuture<Document>> results = new ArrayList<>();
            for (int round = 0; round < 10; round++) {
                results.addAll(submit(office, 20));
                Thread.sleep(60);
            }
            awaitAll(results);
        } finally {
            breaks.stop();
            office.shutdown(); // must not hang: stop() reopened every counter first
        }

        assertTrue(breaks.breaksTaken() > 0, "the scheduler should have sent counters on break");
        assertTrue(office.minOpenCounters() >= 1, "the office must never be fully closed");
        assertEquals(3, office.openCounters(), "stop() reopens every counter");
        office.counters().forEach(counter -> assertFalse(counter.isOnBreak()));
    }

    @Test
    void singleCounterOfficeNeverTakesBreaks() throws Exception {
        Office office = office(1, 1.0);
        CoffeeBreakScheduler breaks = new CoffeeBreakScheduler(List.of(office), simulation(1.0));
        office.start();
        breaks.start();
        try {
            Thread.sleep(3 * CoffeeBreakScheduler.TICK_MS);
            awaitAll(submit(office, 5));
        } finally {
            breaks.stop();
            office.shutdown();
        }
        assertEquals(0, breaks.breaksTaken());
    }
}
