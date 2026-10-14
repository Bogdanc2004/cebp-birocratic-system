package ro.cebp.bureaucracy.faulty.races;

// Copy of correct.CoffeeBreakScheduler with no bugs of its own: the bugs of this program live in Office/Counter.

import ro.cebp.bureaucracy.common.Log;
import ro.cebp.bureaucracy.common.model.SimulationConfig;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends random counters on coffee breaks and brings them back.
 *
 * <p>One scheduler thread ticks every {@link #TICK_MS} ms. At each tick, every open counter goes
 * on break with probability {@code coffeeBreakChance}, and its return is scheduled
 * {@code coffeeBreakMs} later. The office's own rule ({@link Office#startBreak}) refuses a break
 * that would leave it with no open counter.
 */
public final class CoffeeBreakScheduler {

    static final long TICK_MS = 100;

    private final List<Office> offices;
    private final SimulationConfig simulation;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "coffee-break-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger breaksTaken = new AtomicInteger();

    public CoffeeBreakScheduler(List<Office> offices, SimulationConfig simulation) {
        this.offices = List.copyOf(offices);
        this.simulation = simulation;
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        try {
            for (Office office : offices) {
                for (Counter counter : office.counters()) {
                    if (counter.isOnBreak()
                            || ThreadLocalRandom.current().nextDouble() >= simulation.coffeeBreakChance()) {
                        continue;
                    }
                    if (office.startBreak(counter)) {
                        breaksTaken.incrementAndGet();
                        scheduler.schedule(() -> office.endBreak(counter),
                                simulation.coffeeBreakMs().random(), TimeUnit.MILLISECONDS);
                    }
                }
            }
        } catch (RuntimeException e) {
            // If a periodic task throws, scheduleAtFixedRate silently cancels every later tick.
            Log.info("Coffee-break tick failed: %s", e);
        }
    }

    /**
     * Stops the breaks and reopens every counter. Must run before the offices shut down:
     * a counter still on break would never take its poison pill.
     */
    public void stop() throws InterruptedException {
        // shutdownNow also drops the pending "return from break" tasks. We reopen those counters below.
        scheduler.shutdownNow();
        if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
            Log.info("Coffee-break scheduler did not stop in time");
        }
        // The scheduler thread is gone, so nothing can start a new break while we reopen.
        for (Office office : offices) {
            for (Counter counter : office.counters()) {
                office.endBreak(counter);
            }
        }
        Log.info("Coffee breaks over: %d breaks taken", breaksTaken.get());
    }

    public int breaksTaken() {
        return breaksTaken.get();
    }
}
