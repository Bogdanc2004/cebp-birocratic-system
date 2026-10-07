package ro.cebp.bureaucracy.common;

import java.util.Map;

/** Immutable view of the {@link Statistics} at one moment. */
public record StatisticsSnapshot(
        Map<String, Long> issuedPerOffice,
        long waitSamples,
        long totalWaitMs,
        long maxWaitMs,
        int finishedClients) {

    public StatisticsSnapshot {
        issuedPerOffice = Map.copyOf(issuedPerOffice);
    }

    public long totalIssued() {
        return issuedPerOffice.values().stream().mapToLong(Long::longValue).sum();
    }

    public double averageWaitMs() {
        return waitSamples == 0 ? 0 : (double) totalWaitMs / waitSamples;
    }
}
