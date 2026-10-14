package ro.cebp.bureaucracy.faulty.locks;

import ro.cebp.bureaucracy.common.IssuedLedger;
import ro.cebp.bureaucracy.common.Log;
import ro.cebp.bureaucracy.common.Statistics;
import ro.cebp.bureaucracy.common.model.OfficeConfig;
import ro.cebp.bureaucracy.common.model.SimulationConfig;
import ro.cebp.bureaucracy.correct.Request;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * FAULTY Office. Which bugs its counters have is chosen with {@link LockBug}.
 * Break handling ({@link #startBreak}, {@link #endBreak}) is copied from {@code correct.Office}.
 */
public final class Office {

    /** The original P2 behavior, used when no bug set is given. */
    static final Set<LockBug> DEFAULT_BUGS = EnumSet.of(LockBug.BUSY_WAIT, LockBug.BREAK_HOLDING_ARCHIVE);

    private static final long SHUTDOWN_GRACE_MS = 1000;

    private final OfficeConfig config;
    private final BlockingDeque<Request> queue = new LinkedBlockingDeque<>();
    // Shared by the office's counters: every issued document is filed in the archive.
    private final Object archiveLock = new Object();
    private final List<Counter> counters;
    private final List<Thread> threads;
    private final AtomicInteger openCounters;

    public Office(OfficeConfig config, SimulationConfig simulation, Statistics stats, IssuedLedger ledger) {
        this(config, simulation, stats, ledger, DEFAULT_BUGS);
    }

    public Office(OfficeConfig config, SimulationConfig simulation, Statistics stats, IssuedLedger ledger,
                  Set<LockBug> bugs) {
        this.config = config;
        Set<LockBug> enabled = bugs.isEmpty() ? EnumSet.noneOf(LockBug.class) : EnumSet.copyOf(bugs);
        List<Counter> counterList = new ArrayList<>();
        List<Thread> threadList = new ArrayList<>();
        for (int i = 1; i <= config.counters(); i++) {
            Counter counter = new Counter(i, config.name(), queue, archiveLock, simulation, stats, ledger, enabled);
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

    public List<Counter> counters() {
        return counters;
    }

    /** The counter threads, in the same order as {@link #counters()}. For tests and diagnostics. */
    List<Thread> threads() {
        return threads;
    }

    public void start() {
        for (Thread thread : threads) {
            thread.start();
        }
    }

    public void submit(Request request) {
        queue.add(request);
    }

    /** Same rule as the correct version: never leave the office with no open counter. */
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
     * Pills, then the stop flag, then interrupt, each step bounded by a timeout. A counter that
     * fell asleep forever because of BUG [L2] only reacts to the interrupt.
     */
    public void shutdown() throws InterruptedException {
        for (int i = 0; i < counters.size(); i++) {
            queue.add(new Request(0, null, List.of(), new CompletableFuture<>()));
        }
        for (Counter counter : counters) {
            counter.stop();
        }
        for (Thread thread : threads) {
            thread.join(SHUTDOWN_GRACE_MS);
            if (thread.isAlive()) {
                Log.info("Office '%s': %s did not stop, interrupting it", config.name(), thread.getName());
                thread.interrupt();
                thread.join(SHUTDOWN_GRACE_MS);
            }
        }
    }

    static boolean isPoisonPill(Request request) {
        return request.wanted() == null;
    }
}
