package ro.cebp.bureaucracy.faulty.races;

import ro.cebp.bureaucracy.common.Statistics;
import ro.cebp.bureaucracy.common.StatisticsSnapshot;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * FAULTY on purpose. Same job as {@code ConcurrentStatistics}, without any synchronization.
 *
 * <p>BUG [R2] — lost updates. Every update is a read-modify-write: read the old value,
 * compute the new one, write it back. Two threads can read the same old value, and then
 * one of the two increments is lost. The {@code paperwork()} pause between the read and
 * the write stands for real bookkeeping work. It only widens the race window so the bug
 * shows up on every run. The race exists without it too.
 *
 * <p>The {@link java.util.HashMap} itself is also not thread-safe. Concurrent {@code put}s
 * during a resize can lose whole entries.
 *
 * <p>Fix: {@code ConcurrentHashMap.computeIfAbsent(..).increment()} with {@code LongAdder},
 * {@code AtomicInteger.incrementAndGet()}, {@code LongAccumulator(Math::max)},
 * or {@code synchronized} methods.
 */
public final class UnsafeStatistics implements Statistics {

    private final Map<String, Integer> issuedPerOffice = new HashMap<>(); // BUG [R2]: not thread-safe
    private long waitSamples;     // BUG [R2]: plain fields, updated with read-modify-write
    private long totalWaitMs;
    private long maxWaitMs;
    private int finishedClients;

    @Override
    public void recordIssued(String office) {
        Integer current = issuedPerOffice.get(office);                  // read
        paperwork();
        issuedPerOffice.put(office, current == null ? 1 : current + 1); // write: may overwrite another thread's update
    }

    @Override
    public void recordWait(long waitMs) {
        long samples = waitSamples;
        long total = totalWaitMs;
        paperwork();
        waitSamples = samples + 1;
        totalWaitMs = total + waitMs;
        if (waitMs > maxWaitMs) {   // BUG [R2]: check-then-act, a bigger value can be overwritten
            maxWaitMs = waitMs;
        }
    }

    @Override
    public void clientFinished() {
        int finished = finishedClients;
        paperwork();
        finishedClients = finished + 1;
    }

    @Override
    public StatisticsSnapshot snapshot() {
        Map<String, Long> issued = new TreeMap<>();
        issuedPerOffice.forEach((office, count) -> issued.put(office, count.longValue()));
        return new StatisticsSnapshot(issued, waitSamples, totalWaitMs, maxWaitMs, finishedClients);
    }

    /** Widens the race window (see the class comment). */
    private static void paperwork() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
