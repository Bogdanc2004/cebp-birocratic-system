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

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Copy of {@code correct.Simulation} running the faulty-locks {@link Office}s with the chosen
 * {@link LockBug}s. Used by {@code LocksMain} (P2, L2) and by {@link Benchmark}.
 * The deadlock and livelock scenarios can run through it too.
 */
public final class LocksSimulation {

    private final Bureaucracy bureaucracy;
    private final Set<LockBug> bugs;
    private final Statistics stats = new ConcurrentStatistics();

    public LocksSimulation(Bureaucracy bureaucracy, Set<LockBug> bugs) {
        this.bureaucracy = bureaucracy;
        this.bugs = Set.copyOf(bugs);
    }

    public SimulationResult run() throws InterruptedException {
        SimulationConfig sim = bureaucracy.simulation();
        IssuedLedger ledger = new IssuedLedger();

        Map<String, Office> officeByName = new HashMap<>();
        List<Office> offices = new ArrayList<>();
        for (OfficeConfig config : bureaucracy.offices()) {
            Office office = new Office(config, sim, stats, ledger, bugs);
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

        Log.info("Opening %d offices for %d clients, bugs %s", offices.size(), plans.size(), bugs);
        long start = System.nanoTime();
        boolean finishedInTime;
        try {
            offices.forEach(Office::start);
            breaks.start();

            try (ExecutorService clients = Executors.newThreadPerTaskExecutor(
                    Thread.ofVirtual().name("client-", 0).factory())) {
                plans.forEach((id, plan) ->
                        clients.submit(new Client(id, plan, officeByDocument, stats, done)));

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
