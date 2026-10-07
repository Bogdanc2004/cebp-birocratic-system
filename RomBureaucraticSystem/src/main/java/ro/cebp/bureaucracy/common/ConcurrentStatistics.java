package ro.cebp.bureaucracy.common;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe statistics without any explicit lock.
 * <ul>
 *   <li>{@link ConcurrentHashMap#computeIfAbsent} creates the counter of an office atomically,
 *       so two threads can never create two different counters for the same office.</li>
 *   <li>{@link LongAdder} spreads increments over several cells, so many counters that issue
 *       documents at the same time do not all compete for one memory location
 *       (cheaper than {@code AtomicLong} under heavy write contention).</li>
 *   <li>{@link LongAccumulator} with {@code Math::max} keeps a maximum without a
 *       read-compare-write race.</li>
 * </ul>
 */
public final class ConcurrentStatistics implements Statistics {

    private final ConcurrentHashMap<String, LongAdder> issuedPerOffice = new ConcurrentHashMap<>();
    private final LongAdder waitSamples = new LongAdder();
    private final LongAdder totalWaitMs = new LongAdder();
    private final LongAccumulator maxWaitMs = new LongAccumulator(Math::max, 0);
    private final AtomicInteger finishedClients = new AtomicInteger();

    @Override
    public void recordIssued(String office) {
        issuedPerOffice.computeIfAbsent(office, k -> new LongAdder()).increment();
    }

    @Override
    public void recordWait(long waitMs) {
        waitSamples.increment();
        totalWaitMs.add(waitMs);
        maxWaitMs.accumulate(waitMs);
    }

    @Override
    public void clientFinished() {
        finishedClients.incrementAndGet();
    }

    @Override
    public StatisticsSnapshot snapshot() {
        Map<String, Long> issued = new TreeMap<>();
        issuedPerOffice.forEach((office, count) -> issued.put(office, count.sum()));
        return new StatisticsSnapshot(issued, waitSamples.sum(), totalWaitMs.sum(),
                maxWaitMs.get(), finishedClients.get());
    }
}
