package ro.cebp.bureaucracy.faulty.races;

import ro.cebp.bureaucracy.common.Bureaucracy;
import ro.cebp.bureaucracy.common.ClientPlans;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * FAULTY copy of {@code correct.Simulation}. Differences (diff the two files to see them):
 * <ul>
 *   <li>statistics are kept in {@link UnsafeStatistics} (BUG [R2]);</li>
 *   <li>offices open in parallel and register themselves in {@link OfficeRegistry}
 *       (BUG [R6]); clients find their office through that registry.</li>
 * </ul>
 */
public final class RacesSimulation {

    private final Bureaucracy bureaucracy;
    private final Statistics stats = new UnsafeStatistics(); // BUG [R2]

    public RacesSimulation(Bureaucracy bureaucracy) {
        this.bureaucracy = bureaucracy;
    }

    public SimulationResult run() throws InterruptedException {
        Counter.resetSerial();
        SimulationConfig sim = bureaucracy.simulation();
        IssuedLedger ledger = new IssuedLedger();

        List<Office> offices = openOfficesInParallel(sim, ledger);

        Map<String, Office> byDocument = new HashMap<>();
        for (DocumentType doc : bureaucracy.documents().values()) {
            // BUG [R6]: the registry we see now may be missing offices registered in another instance.
            Office office = OfficeRegistry.getInstance().lookup(doc.issuingOffice());
            if (office == null) {
                Log.info("Office '%s' not found in the registry: clients needing %s will fail",
                        doc.issuingOffice(), doc.name());
            }
            byDocument.put(doc.name(), office);
        }
        Map<String, Office> officeByDocument = Collections.unmodifiableMap(byDocument); // may contain nulls
        Map<Integer, List<DocumentType>> plans = ClientPlans.assign(bureaucracy);

        CountDownLatch done = new CountDownLatch(plans.size());
        CoffeeBreakScheduler breaks = new CoffeeBreakScheduler(List.copyOf(offices), sim);

        Log.info("Opening %d offices for %d clients", offices.size(), plans.size());
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

    /**
     * Each office is created and registered on its own thread. A latch releases them all
     * at the same moment, which is what makes the R6 race on {@code getInstance()} likely.
     */
    private List<Office> openOfficesInParallel(SimulationConfig sim, IssuedLedger ledger) throws InterruptedException {
        ConcurrentLinkedQueue<Office> created = new ConcurrentLinkedQueue<>();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> openers = new ArrayList<>();
        for (OfficeConfig config : bureaucracy.offices()) {
            openers.add(Thread.ofPlatform().name("open-" + config.name()).start(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                Office office = new Office(config, sim, stats, ledger);
                created.add(office);
                OfficeRegistry.getInstance().register(office); // BUG [R6]
            }));
        }
        go.countDown();
        for (Thread opener : openers) {
            opener.join();
        }
        return List.copyOf(created);
    }
}
