package ro.cebp.bureaucracy.common;

import ro.cebp.bureaucracy.common.model.Document;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Append-only record of every document issued during a run. It is the "ground truth"
 * used by the {@link InvariantChecker}, so it stays thread-safe in every version,
 * including the faulty ones.
 */
public final class IssuedLedger {

    // Lock-free (CAS-based) queue: counters of different offices append in parallel.
    private final ConcurrentLinkedQueue<Document> issued = new ConcurrentLinkedQueue<>();

    /** Called by a counter every time it issues a document. */
    public void record(Document document) {
        issued.add(document);
    }

    public List<Document> documents() {
        return List.copyOf(issued);
    }
}
