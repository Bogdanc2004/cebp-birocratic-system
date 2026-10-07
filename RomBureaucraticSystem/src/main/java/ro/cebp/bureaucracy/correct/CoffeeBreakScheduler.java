package ro.cebp.bureaucracy.correct;

import ro.cebp.bureaucracy.common.model.SimulationConfig;

import java.util.List;

/**
 * OWNER: M3 (Coffee breaks). STUB: only the agreed signatures, no logic.
 *
 * <p>Expected behavior: a {@code ScheduledExecutorService} that periodically sends random
 * counters on break (probability {@code simulation.coffeeBreakChance()}, duration
 * {@code simulation.coffeeBreakMs().random()}) and reopens them afterwards. A request
 * must never be lost because of a break.
 */
public final class CoffeeBreakScheduler {

    public CoffeeBreakScheduler(List<Office> offices, SimulationConfig simulation) {
        // TODO M3
    }

    public void start() {
        throw new UnsupportedOperationException("TODO M3: CoffeeBreakScheduler.start");
    }

    /** Cancels future breaks, reopens every closed counter and stops the scheduler thread. */
    public void stop() throws InterruptedException {
        throw new UnsupportedOperationException("TODO M3: CoffeeBreakScheduler.stop");
    }
}
