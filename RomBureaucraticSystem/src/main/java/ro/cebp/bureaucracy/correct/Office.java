package ro.cebp.bureaucracy.correct;

import ro.cebp.bureaucracy.common.IssuedLedger;
import ro.cebp.bureaucracy.common.Statistics;
import ro.cebp.bureaucracy.common.model.OfficeConfig;
import ro.cebp.bureaucracy.common.model.SimulationConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;

// Manages a single bureaucratic office, its shared request queue, and counter threads.
public final class Office {

    private final OfficeConfig config;
    // A deque, so a counter that goes on break can hand its client back to the FRONT of the queue.
    private final BlockingDeque<Request> queue = new LinkedBlockingDeque<>();
    private final List<Counter> counters;
    private final List<Thread> threads;

    // Coffee breaks: how many counters are open right now, and the lowest value ever reached.
    private final AtomicInteger openCounters;
    private final AtomicInteger minOpenCounters;

    public Office(OfficeConfig config, SimulationConfig simulation, Statistics stats, IssuedLedger ledger) {
        this.config = config;
        List<Counter> counterList = new ArrayList<>();
        List<Thread> threadList = new ArrayList<>();
        for (int i = 1; i <= config.counters(); i++) {
            Counter counter = new Counter(i, config.name(), queue, simulation, stats, ledger);
            counterList.add(counter);
            Thread thread = new Thread(counter, config.name() + "-counter-" + i);
            threadList.add(thread);
        }
        this.counters = List.copyOf(counterList);
        this.threads = List.copyOf(threadList);
        this.openCounters = new AtomicInteger(config.counters());
        this.minOpenCounters = new AtomicInteger(config.counters());
    }

    public String name() {
        return config.name();
    }

    // Exposes counters for coffee break coordination.
    public List<Counter> counters() {
        return counters;
    }

    // Starts all counter worker threads.
    public void start() {
        for (Thread thread : threads) {
            thread.start();
        }
    }

    // Submits a client request into the office queue.
    public void submit(Request request) {
        try {
            queue.put(request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while submitting request to " + config.name(), e);
        }
    }

    /**
     * Sends {@code counter} on a coffee break, unless that would leave the office with no open
     * counter, or the counter is already on break.
     *
     * <p>Rule "the office never closes completely": the open-counter count goes down only with a
     * {@code compareAndSet} that still sees a value greater than 1. A plain
     * {@code if (open > 1) open--} would be check-then-act: two counters could both pass the
     * check at the same moment and both leave.
     *
     * @return true if the counter is now on break
     */
    public boolean startBreak(Counter counter) {
        while (true) {
            int open = openCounters.get();
            if (open <= 1) {
                return false;
            }
            if (openCounters.compareAndSet(open, open - 1)) {
                minOpenCounters.accumulateAndGet(open - 1, Math::min);
                break;
            }
            // Another counter changed the count in between: read it again and retry.
        }
        if (!counter.goOnBreak()) {
            openCounters.incrementAndGet(); // it was already on break: give the slot back
            return false;
        }
        return true;
    }

    /** Ends the break of {@code counter}. Does nothing if it is not on break, so it is safe to call twice. */
    public void endBreak(Counter counter) {
        if (counter.returnFromBreak()) {
            openCounters.incrementAndGet();
        }
    }

    public int openCounters() {
        return openCounters.get();
    }

    /** Lowest number of open counters ever reached. The invariant checker requires it to be at least 1. */
    public int minOpenCounters() {
        return minOpenCounters.get();
    }

    // Sends poison pills to counters and waits for all threads to terminate.
    public void shutdown() throws InterruptedException {
        for (int i = 0; i < counters.size(); i++) {
            queue.put(new Request(0, null, List.of(), new CompletableFuture<>()));
        }
        for (Thread thread : threads) {
            thread.join();
        }
    }

    // Helper to identify poison pill requests.
    static boolean isPoisonPill(Request request) {
        return request.wanted() == null;
    }
}
