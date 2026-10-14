package ro.cebp.bureaucracy.correct;

import ro.cebp.bureaucracy.common.IssuedLedger;
import ro.cebp.bureaucracy.common.Log;
import ro.cebp.bureaucracy.common.Statistics;
import ro.cebp.bureaucracy.common.model.Document;
import ro.cebp.bureaucracy.common.model.DocumentType;
import ro.cebp.bureaucracy.common.model.SimulationConfig;

import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// Worker thread representing a counter desk in an office.
public final class Counter implements Runnable {

    // Global lock-free serial number generator across all counters.
    private static final AtomicLong SERIAL_GENERATOR = new AtomicLong(1);

    private final int id;
    private final String officeName;
    private final BlockingDeque<Request> queue;
    private final SimulationConfig simulation;
    private final Statistics stats;
    private final IssuedLedger ledger;

    // Coffee-break gate. The flag is atomic so it can be read without the lock;
    // waiting and waking up happen on pauseLock.
    private final AtomicBoolean onBreak = new AtomicBoolean(false);
    private final Object pauseLock = new Object();
    private final AtomicInteger served = new AtomicInteger();

    public Counter(int id,
                   String officeName,
                   BlockingDeque<Request> queue,
                   SimulationConfig simulation,
                   Statistics stats,
                   IssuedLedger ledger) {
        this.id = id;
        this.officeName = officeName;
        this.queue = queue;
        this.simulation = simulation;
        this.stats = stats;
        this.ledger = ledger;
    }

    public int id() {
        return id;
    }

    public boolean isOnBreak() {
        return onBreak.get();
    }

    /** Number of requests this counter has served. */
    public int served() {
        return served.get();
    }

    /** Marks the counter as on break. Only {@link Office#startBreak} calls it. Returns false if already on break. */
    boolean goOnBreak() {
        if (!onBreak.compareAndSet(false, true)) {
            return false;
        }
        Log.info("[%s-Counter-%d] Going on coffee break", officeName, id);
        return true;
    }

    /** Reopens the counter and wakes it. Only {@link Office#endBreak} calls it. Returns false if it was not on break. */
    boolean returnFromBreak() {
        synchronized (pauseLock) {
            // The flag changes under the same lock the counter waits on, so the wake-up cannot
            // slip in between the counter's check and its wait().
            if (!onBreak.compareAndSet(true, false)) {
                return false;
            }
            pauseLock.notifyAll();
        }
        Log.info("[%s-Counter-%d] Returned from coffee break", officeName, id);
        return true;
    }

    @Override
    public void run() {
        try {
            while (true) {
                // The gate is checked BEFORE calling the next client, never between calling and serving.
                waitWhileOnBreak();

                Request request = queue.take();

                // Poison pill terminates counter worker.
                if (Office.isPoisonPill(request)) {
                    break;
                }

                // Sent on break while waiting in take(): hand the client back to the front of the
                // queue, so an open counter of the office serves them next. Nobody is lost.
                if (onBreak.get()) {
                    queue.putFirst(request);
                    continue;
                }

                serve(request);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void waitWhileOnBreak() throws InterruptedException {
        synchronized (pauseLock) {
            // while, not if: after waking up, check the condition again (spurious wake-ups, or
            // being woken for a break that started again).
            while (onBreak.get()) {
                pauseLock.wait();
            }
        }
    }

    private void serve(Request request) throws InterruptedException {
        // Simulate desk service time.
        int serviceMs = simulation.serviceTimeMs().random();
        if (serviceMs > 0) {
            Thread.sleep(serviceMs);
        }

        // Verify that all required prerequisite documents are present.
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

        // Issue new document with atomic unique serial number.
        long serial = SERIAL_GENERATOR.getAndIncrement();
        Document doc = new Document(wanted, request.clientId(), serial);

        // Record to ledger, update statistics and complete client future.
        ledger.record(doc);
        stats.recordIssued(officeName);
        served.incrementAndGet();
        request.result().complete(doc);
    }
}
