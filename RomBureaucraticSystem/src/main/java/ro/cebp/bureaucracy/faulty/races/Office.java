package ro.cebp.bureaucracy.faulty.races;

import ro.cebp.bureaucracy.common.IssuedLedger;
import ro.cebp.bureaucracy.common.Log;
import ro.cebp.bureaucracy.common.Statistics;
import ro.cebp.bureaucracy.common.model.OfficeConfig;
import ro.cebp.bureaucracy.common.model.SimulationConfig;
import ro.cebp.bureaucracy.correct.Request;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

// FAULTY Office holding a plain unsynchronized ArrayList queue (BUG R1).
public final class Office {

    private static final long SHUTDOWN_GRACE_MS = 1000;

    private final OfficeConfig config;
    // BUG [R1]: Plain ArrayList queue without synchronization causes race conditions.
    private final List<Request> queue = new ArrayList<>();
    private final List<Counter> counters;
    private final List<Thread> threads;
    private final AtomicInteger openCounters;

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
    }

    public String name() {
        return config.name();
    }

    // Exposes counters for coffee break coordination.
    public List<Counter> counters() {
        return counters;
    }

    // Starts all counter threads.
    public void start() {
        for (Thread thread : threads) {
            thread.start();
        }
    }

    // BUG [R1]: Plain ArrayList add without synchronization can corrupt internal buffer or lose elements.
    public void submit(Request request) {
        queue.add(request);
    }

    // Coffee breaks: copied from correct.Office (not one of the bugs under study).
    public boolean startBreak(Counter counter) {
        while (true) {
            int open = openCounters.get();
            if (open <= 1) {
                return false;
            }
            if (openCounters.compareAndSet(open, open - 1)) {
                break;
            }
        }
        if (!counter.goOnBreak()) {
            openCounters.incrementAndGet();
            return false;
        }
        return true;
    }

    public void endBreak(Counter counter) {
        if (counter.returnFromBreak()) {
            openCounters.incrementAndGet();
        }
    }

    /**
     * Shuts down the counters. This is not one of the bugs under study: it must always end, so
     * that the run can report the corrupted data instead of hanging.
     *
     * <p>Poison pills alone are not enough here. Because of BUG [R1], the unsynchronized list can
     * lose a pill, or hand the same pill to two counters, and then some counter never sees one.
     * So: pills first, then the stop flag, then interrupt (for a counter blocked on a break),
     * each step bounded by a timeout.
     */
    public void shutdown() throws InterruptedException {
        for (int i = 0; i < counters.size(); i++) {
            queue.add(new Request(0, null, List.of(), new CompletableFuture<>()));
        }
        joinAll();

        counters.forEach(Counter::stop);
        joinAll();

        for (Thread thread : threads) {
            if (thread.isAlive()) {
                thread.interrupt();
                thread.join(SHUTDOWN_GRACE_MS);
            }
        }
        long stuck = threads.stream().filter(Thread::isAlive).count();
        if (stuck > 0) {
            Log.info("Office '%s': %d counter(s) did not stop", config.name(), stuck);
        }
    }

    private void joinAll() throws InterruptedException {
        for (Thread thread : threads) {
            thread.join(SHUTDOWN_GRACE_MS);
        }
    }

    // Identifies poison pill requests.
    static boolean isPoisonPill(Request request) {
        return request.wanted() == null;
    }
}
