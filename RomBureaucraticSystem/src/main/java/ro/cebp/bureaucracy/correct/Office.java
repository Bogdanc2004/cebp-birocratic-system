package ro.cebp.bureaucracy.correct;

import ro.cebp.bureaucracy.common.IssuedLedger;
import ro.cebp.bureaucracy.common.Statistics;
import ro.cebp.bureaucracy.common.model.OfficeConfig;
import ro.cebp.bureaucracy.common.model.SimulationConfig;

/**
 * OWNER: M2 (Queues &amp; Counters). STUB: only the agreed signatures, no logic.
 *
 * <p>Expected behavior:
 * <ul>
 *   <li>one {@code BlockingQueue<Request>} shared by {@code config.counters()} counter threads;</li>
 *   <li>a counter takes a request, works for {@code simulation.serviceTimeMs().random()} ms,
 *       checks that {@code presented} contains every required document, creates a
 *       {@code Document} with a globally unique serial number, then calls
 *       {@code ledger.record(doc)}, {@code stats.recordIssued(name())} and
 *       {@code request.result().complete(doc)};</li>
 *   <li>{@link #shutdown()} stops all counters (poison pill) and waits for them to end.</li>
 * </ul>
 * M3 needs a way to close and reopen counters for coffee breaks. Agree on it with M2.
 */
public final class Office {

    private final OfficeConfig config;

    public Office(OfficeConfig config, SimulationConfig simulation, Statistics stats, IssuedLedger ledger) {
        this.config = config;
        // TODO M2
    }

    public String name() {
        return config.name();
    }

    /** Starts the counter threads. */
    public void start() {
        throw new UnsupportedOperationException("TODO M2: Office.start");
    }

    /** Puts a request in this office's queue. Called by client threads. */
    public void submit(Request request) {
        throw new UnsupportedOperationException("TODO M2: Office.submit");
    }

    /** Stops the counters and waits for them to terminate. */
    public void shutdown() throws InterruptedException {
        throw new UnsupportedOperationException("TODO M2: Office.shutdown");
    }

    // Helper to identify poison pill requests.
    static boolean isPoisonPill(Request request) {
        return request.wanted() == null;
    }
}
