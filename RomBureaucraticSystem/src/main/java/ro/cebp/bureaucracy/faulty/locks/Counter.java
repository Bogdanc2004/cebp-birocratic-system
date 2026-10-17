package ro.cebp.bureaucracy.faulty.locks;

import ro.cebp.bureaucracy.common.IssuedLedger;
import ro.cebp.bureaucracy.common.Log;
import ro.cebp.bureaucracy.common.Statistics;
import ro.cebp.bureaucracy.common.model.Document;
import ro.cebp.bureaucracy.common.model.DocumentType;
import ro.cebp.bureaucracy.common.model.SimulationConfig;
import ro.cebp.bureaucracy.correct.Request;

import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * FAULTY Counter. Its bugs are switched on one by one with {@link LockBug}:
 * <ul>
 *   <li>BUG [P2] {@link LockBug#BUSY_WAIT}: spins while the queue is empty;</li>
 *   <li>BUG [P2] {@link LockBug#BREAK_HOLDING_ARCHIVE}: keeps the archive lock for the whole break;</li>
 *   <li>BUG [L2] {@link LockBug#LOST_WAKEUP}: can miss the "back from break" signal and sleep forever;</li>
 *   <li>BUG [L] {@link LockBug#POLITE_LIVELOCK}: retries shared resources in sync forever.</li>
 * </ul>
 * With no bug enabled it behaves correctly.
 */
public final class Counter implements Runnable {

    private static final AtomicLong SERIAL_GENERATOR = new AtomicLong(1);
    // How long a counter blocks for the next client before re-checking breaks and the stop flag.
    private static final long POLL_MS = 20;

    private final int id;
    private final String officeName;
    private final BlockingDeque<Request> queue;
    private final Object archiveLock;
    private final SimulationConfig simulation;
    private final Statistics stats;
    private final IssuedLedger ledger;
    private final Set<LockBug> bugs;
    private final ResourcePool resourcePool;

    private final AtomicBoolean onBreak = new AtomicBoolean(false);
    private final Object pauseLock = new Object();
    private volatile boolean running = true;

    Counter(int id,
            String officeName,
            BlockingDeque<Request> queue,
            Object archiveLock,
            SimulationConfig simulation,
            Statistics stats,
            IssuedLedger ledger,
            Set<LockBug> bugs,
            ResourcePool resourcePool) {
        this.id = id;
        this.officeName = officeName;
        this.queue = queue;
        this.archiveLock = archiveLock;
        this.simulation = simulation;
        this.stats = stats;
        this.ledger = ledger;
        this.bugs = bugs;
        this.resourcePool = resourcePool;
    }

    public int id() {
        return id;
    }

    public boolean isOnBreak() {
        return onBreak.get();
    }

    boolean goOnBreak() {
        if (!onBreak.compareAndSet(false, true)) {
            return false;
        }
        Log.info("[%s-Counter-%d] Going on coffee break", officeName, id);
        return true;
    }

    boolean returnFromBreak() {
        synchronized (pauseLock) {
            if (!onBreak.compareAndSet(true, false)) {
                return false;
            }
            // notifyAll() vs notify() is NOT the L2 bug: only this counter ever waits on pauseLock.
            // The bug is that this signal may be sent before the counter starts waiting (see lostWakeupBreak).
            pauseLock.notifyAll();
        }
        Log.info("[%s-Counter-%d] Returned from coffee break", officeName, id);
        return true;
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        try {
            while (running) {
                handleBreak();

                Request request = nextRequest();
                if (request == null) {
                    continue; // nothing yet, a break started, or we are stopping
                }
                if (Office.isPoisonPill(request)) {
                    break;
                }
                if (onBreak.get()) {
                    // Sent on break while waiting for a client: give the client back (as in the correct version).
                    queue.putFirst(request);
                    continue;
                }
                serve(request);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Request nextRequest() throws InterruptedException {
        if (bugs.contains(LockBug.BUSY_WAIT)) {
            // BUG [P2]: busy waiting. The thread never blocks: it keeps a CPU core at 100% asking
            // "anyone there?" while the queue is empty.
            while (queue.isEmpty() && running && !onBreak.get()) {
                Thread.onSpinWait();
            }
            return queue.poll();
        }
        // Correct: block until a client arrives (with a short timeout, to notice breaks and stop).
        return queue.poll(POLL_MS, TimeUnit.MILLISECONDS);
    }

    private void handleBreak() throws InterruptedException {
        if (!onBreak.get()) {
            return;
        }
        if (bugs.contains(LockBug.LOST_WAKEUP)) {
            lostWakeupBreak();
        } else if (bugs.contains(LockBug.BREAK_HOLDING_ARCHIVE)) {
            // BUG [P2]: the whole break is spent holding the archive lock. wait() releases only
            // pauseLock, NOT archiveLock, so every other counter of the office blocks in serve().
            synchronized (archiveLock) {
                waitWhileOnBreak();
            }
        } else {
            waitWhileOnBreak();
        }
    }

    // Correct way to wait: the condition is checked and waited on under the same lock, in a loop.
    private void waitWhileOnBreak() throws InterruptedException {
        synchronized (pauseLock) {
            while (onBreak.get()) {
                pauseLock.wait();
            }
        }
    }

    /**
     * BUG [L2] â€” lost wakeup. The counter decided it is on break (the check in handleBreak, made
     * without holding pauseLock), then drinks its coffee, and only then waits for the signal.
     * If the supervisor ends the break during the coffee, returnFromBreak() notifies while
     * nobody is waiting yet, so the signal is lost. The counter then waits with no timeout and
     * without re-checking {@code onBreak}, which is already false: it sleeps forever.
     * The office counts it as open, but it never serves anyone again.
     *
     * <p>Fix: check the condition and wait under the same lock, in a loop
     * ({@link #waitWhileOnBreak()}).
     */
    private void lostWakeupBreak() throws InterruptedException {
        Thread.sleep(simulation.coffeeBreakMs().max()); // the coffee: the reopen signal usually arrives now
        synchronized (pauseLock) {
            pauseLock.wait(); // BUG [L2]: no while (onBreak) loop and no timeout
        }
    }

    private void serve(Request request) throws InterruptedException {
        int serviceMs = simulation.serviceTimeMs().random();
        if (serviceMs > 0) {
            Thread.sleep(serviceMs);
        }

        DocumentType wanted = request.wanted();
        Set<String> presentedTypes = request.presented().stream()
                .map(d -> d.type().name())
                .collect(Collectors.toSet());

        for (String req : wanted.requires()) {
            if (!presentedTypes.contains(req)) {
                IllegalStateException error = new IllegalStateException(
                        String.format("Client %d missing prerequisite %s for %s at office %s",
                                request.clientId(), req, wanted.name(), officeName));
                request.result().completeExceptionally(error);
                return;
            }
        }

        resourcePool.acquire(wanted.uses());
        try {
            Document doc;
            // Short critical section: filing the document in the office archive.
            synchronized (archiveLock) {
                long serial = SERIAL_GENERATOR.getAndIncrement();
                doc = new Document(wanted, request.clientId(), serial);
                ledger.record(doc);
                stats.recordIssued(officeName);
            }
            request.result().complete(doc);
        } finally {
            resourcePool.release(wanted.uses());
        }
    }
}
