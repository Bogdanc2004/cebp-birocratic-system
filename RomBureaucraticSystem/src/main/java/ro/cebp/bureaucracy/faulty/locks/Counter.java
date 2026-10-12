package ro.cebp.bureaucracy.faulty.locks;

import ro.cebp.bureaucracy.common.IssuedLedger;
import ro.cebp.bureaucracy.common.Log;
import ro.cebp.bureaucracy.common.Statistics;
import ro.cebp.bureaucracy.common.model.Document;
import ro.cebp.bureaucracy.common.model.DocumentType;
import ro.cebp.bureaucracy.common.model.SimulationConfig;
import ro.cebp.bureaucracy.correct.Request;

import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// FAULTY Counter worker implementing BUG P2 (busy waiting, sleep under lock) and BUG L2 (lost wakeup).
public final class Counter implements Runnable {

    private static final AtomicLong SERIAL_GENERATOR = new AtomicLong(1);

    private final int id;
    private final String officeName;
    private final Queue<Request> queue;
    private final Object archiveLock;
    private final SimulationConfig simulation;
    private final Statistics stats;
    private final IssuedLedger ledger;

    private final AtomicBoolean onBreak = new AtomicBoolean(false);
    private final Object pauseLock = new Object();
    private volatile boolean running = true;

    public Counter(int id,
                   String officeName,
                   Queue<Request> queue,
                   Object archiveLock,
                   SimulationConfig simulation,
                   Statistics stats,
                   IssuedLedger ledger) {
        this.id = id;
        this.officeName = officeName;
        this.queue = queue;
        this.archiveLock = archiveLock;
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

    // Sends counter on break.
    public void sendOnBreak() {
        onBreak.set(true);
        Log.info("[%s-Counter-%d] Going on coffee break", officeName, id);
    }

    // BUG [L2]: notify() instead of notifyAll() causes lost wakeup when multiple threads wait.
    public void resumeFromBreak() {
        synchronized (pauseLock) {
            onBreak.set(false);
            // BUG [L2]: Single notification can be lost or wake wrong thread.
            pauseLock.notify();
        }
        Log.info("[%s-Counter-%d] Returned from coffee break", officeName, id);
    }

    // Stops worker loop.
    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        try {
            while (running) {
                checkBreak();

                // BUG [P2]: Busy-waiting while queue is empty burns 100% CPU instead of blocking.
                while (queue.isEmpty() && running) {
                    Thread.onSpinWait();
                }

                if (!running) {
                    break;
                }

                Request request = queue.poll();
                if (request == null) {
                    continue;
                }

                if (Office.isPoisonPill(request)) {
                    break;
                }

                checkBreak();
                serve(request);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // BUG [L2]: wait() inside if instead of while loop leads to missed signals.
    private void checkBreak() throws InterruptedException {
        if (onBreak.get()) {
            // BUG [P2]: Holding archive lock during coffee break blocks other counters in office.
            synchronized (archiveLock) {
                synchronized (pauseLock) {
                    // BUG [L2]: Uses if instead of while loop before wait().
                    if (onBreak.get()) {
                        int breakMs = simulation.coffeeBreakMs().random();
                        Thread.sleep(breakMs);
                        pauseLock.wait(50);
                    }
                }
            }
        }
    }

    private void serve(Request request) throws InterruptedException {
        // Shared archive lock acquired to serve request.
        synchronized (archiveLock) {
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

            long serial = SERIAL_GENERATOR.getAndIncrement();
            Document doc = new Document(wanted, request.clientId(), serial);
            ledger.record(doc);
            stats.recordIssued(officeName);
            request.result().complete(doc);
        }
    }
}
