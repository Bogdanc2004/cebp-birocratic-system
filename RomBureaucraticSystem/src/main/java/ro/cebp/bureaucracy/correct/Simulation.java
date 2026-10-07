package ro.cebp.bureaucracy.correct;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Wires the whole system together and runs it once.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Everything shared is built on the main thread <b>before</b> any thread starts
 *       (offices, the document → office map, the client plans). Thread start creates a
 *       happens-before edge, so all threads see these objects fully initialized.</li>
 *   <li>Counters and the coffee-break scheduler start, then one virtual thread per client.</li>
 *   <li>The main thread waits on a {@link CountDownLatch} that every client counts down
 *       when it is done, with a timeout so a stuck run cannot hang forever.</li>
 *   <li>Shutdown in dependency order: clients → coffee breaks → offices. Only then are the
 *       statistics read, so the numbers are final.</li>
 * </ol>
 */
public final class Simulation {

    private final Bureaucracy bureaucracy;
    private final Statistics stats;

    public Simulation(Bureaucracy bureaucracy) {
        this(bureaucracy, new ConcurrentStatistics());
    }

    public Simulation(Bureaucracy bureaucracy, Statistics stats) {
        this.bureaucracy = bureaucracy;
        this.stats = stats;
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
        // Read-only from now on: clients only call get(), so no synchronization is needed.
        Map<String, Office> officeByDocument = Map.copyOf(byDocument);
        Map<Integer, List<DocumentType>> plans = ClientPlans.assign(bureaucracy);

        CountDownLatch done = new CountDownLatch(plans.size());
        CoffeeBreakScheduler breaks = new CoffeeBreakScheduler(List.copyOf(offices), sim);

        Log.info("Opening %d offices for %d clients", offices.size(), plans.size());
        long start = System.nanoTime();
        boolean finishedInTime;
        try {
            offices.forEach(Office::start);
            breaks.start();

            // close() at the end of the try block waits for every client thread to end.
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
