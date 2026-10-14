package ro.cebp.bureaucracy.faulty.locks;

import ro.cebp.bureaucracy.common.Bureaucracy;
import ro.cebp.bureaucracy.common.ClientPlans;
import ro.cebp.bureaucracy.common.ConcurrentStatistics;
import ro.cebp.bureaucracy.common.InvariantChecker;
import ro.cebp.bureaucracy.common.IssuedLedger;
import ro.cebp.bureaucracy.common.Log;
import ro.cebp.bureaucracy.common.SimulationResult;
import ro.cebp.bureaucracy.common.Statistics;
import ro.cebp.bureaucracy.common.StatisticsSnapshot;
import ro.cebp.bureaucracy.common.model.DocumentType;
import ro.cebp.bureaucracy.common.model.OfficeConfig;
import ro.cebp.bureaucracy.common.model.SimulationConfig;
// P1 is measured on its own: everything except the global lock is the correct code.
import ro.cebp.bureaucracy.correct.Client;
import ro.cebp.bureaucracy.correct.CoffeeBreakScheduler;
import ro.cebp.bureaucracy.correct.Office;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * FAULTY copy of {@code correct.Simulation}, with one difference.
 *
 * <p>BUG [P1] — one global lock ("only one citizen in the building at a time"). Each client
 * does its whole journey inside {@code synchronized (BUILDING)}. The result is still correct,
 * because a lock that is too coarse is safe, but every client waits for all the others:
 * offices, counters and virtual threads sit idle, and total time grows linearly with the
 * number of clients. {@link Benchmark} measures it.
 *
 * <p>Also note that virtual threads blocked on a contended monitor wait without using a
 * carrier thread (Java 24+), but they still wait. The bottleneck is the lock, not the threads.
 *
 * <p>Fix: protect only the shared state that needs it, with the narrowest lock possible
 * (per-office queue, atomic counters), as in the correct version.
 */
public final class GlobalLockSimulation {

    private static final Object BUILDING = new Object(); // BUG [P1]: one lock for the whole system

    private final Bureaucracy bureaucracy;
    private final Statistics stats = new ConcurrentStatistics();

    public GlobalLockSimulation(Bureaucracy bureaucracy) {
        this.bureaucracy = bureaucracy;
    }

    public SimulationResult run() throws InterruptedException {
        SimulationConfig sim = bureaucracy.simulation();
        IssuedLedger ledger = new IssuedLedger();

        Map<String, Office> officeByName = new HashMap<>();
        List<Office> offices = new ArrayList<>();
        for (OfficeConfig config : bureaucracy.offices()) {
            Office office = new Office(config, sim, stats, ledger);
            officeByName.put(config.name(), office);
            offices.add(office);
        }
        Map<String, Office> byDocument = new HashMap<>();
        for (DocumentType doc : bureaucracy.documents().values()) {
            byDocument.put(doc.name(), officeByName.get(doc.issuingOffice()));
        }
        Map<String, Office> officeByDocument = Map.copyOf(byDocument);
        Map<Integer, List<DocumentType>> plans = ClientPlans.assign(bureaucracy);

        CountDownLatch done = new CountDownLatch(plans.size());
        CoffeeBreakScheduler breaks = new CoffeeBreakScheduler(List.copyOf(offices), sim);

        Log.info("Opening %d offices for %d clients (one global lock)", offices.size(), plans.size());
        long start = System.nanoTime();
        boolean finishedInTime;
        try {
            offices.forEach(Office::start);
            breaks.start();

            try (ExecutorService clients = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("client-", 0).factory())) {
                plans.forEach((id, plan) -> {
                    Client client = new Client(id, plan, officeByDocument, stats, done);
                    clients.submit(() -> {
                        synchronized (BUILDING) { // BUG [P1]: the whole journey under one lock
                            client.run();
                        }
                    });
                });

                finishedInTime = done.await(sim.timeoutSeconds(), TimeUnit.SECONDS);
                if (!finishedInTime) {
                    Log.info("TIMEOUT after %d s: %d clients still waiting, interrupting them",
                            sim.timeoutSeconds(), done.getCount());
                    clients.shutdownNow();
                }
            }
        } finally {
            breaks.stop();
            for (Office office : offices) {
                office.shutdown();
            }
        }
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        Log.info("All offices closed");

        StatisticsSnapshot snapshot = stats.snapshot();
        List<String> violations = InvariantChecker.check(plans, ledger.documents(), snapshot);
        return new SimulationResult(snapshot, violations, elapsed, finishedInTime);
    }
}
