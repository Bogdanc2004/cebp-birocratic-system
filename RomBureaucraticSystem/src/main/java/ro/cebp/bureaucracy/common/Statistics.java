package ro.cebp.bureaucracy.common;

/**
 * Counters updated by many threads at once (counters, clients).
 * The correct implementation is {@link ConcurrentStatistics}. The faulty versions plug in
 * broken implementations, and the {@link InvariantChecker} catches the difference.
 */
public interface Statistics {

    /** Called by a counter each time it issues a document. */
    void recordIssued(String office);

    /** Called by a client with the time it spent waiting for one document. */
    void recordWait(long waitMs);

    /** Called by a client when it has obtained its target document. */
    void clientFinished();

    /** Copy of the current values. Only exact once all threads have stopped. */
    StatisticsSnapshot snapshot();
}
