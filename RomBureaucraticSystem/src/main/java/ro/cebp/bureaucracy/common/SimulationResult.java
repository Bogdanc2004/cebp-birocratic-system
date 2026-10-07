package ro.cebp.bureaucracy.common;

import java.time.Duration;
import java.util.List;

/**
 * Outcome of one simulation run.
 *
 * @param finishedInTime false if the clients did not all finish before the timeout
 * @param violations     output of the {@link InvariantChecker}
 */
public record SimulationResult(
        StatisticsSnapshot statistics,
        List<String> violations,
        Duration elapsed,
        boolean finishedInTime) {

    public SimulationResult {
        violations = List.copyOf(violations);
    }

    public boolean ok() {
        return finishedInTime && violations.isEmpty();
    }
}
