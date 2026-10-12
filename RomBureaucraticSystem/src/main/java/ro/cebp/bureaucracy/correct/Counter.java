package ro.cebp.bureaucracy.correct;

import ro.cebp.bureaucracy.common.IssuedLedger;
import ro.cebp.bureaucracy.common.Log;
import ro.cebp.bureaucracy.common.Statistics;
import ro.cebp.bureaucracy.common.model.Document;
import ro.cebp.bureaucracy.common.model.DocumentType;
import ro.cebp.bureaucracy.common.model.SimulationConfig;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

// Worker thread representing a counter desk in an office.
public final class Counter implements Runnable {

    // Global lock-free serial number generator across all counters.
    private static final AtomicLong SERIAL_GENERATOR = new AtomicLong(1);

    private final int id;
    private final String officeName;
    private final BlockingQueue<Request> queue;
    private final SimulationConfig simulation;
    private final Statistics stats;
    private final IssuedLedger ledger;

    private final AtomicBoolean onBreak = new AtomicBoolean(false);
    private final Object pauseLock = new Object();
    private volatile boolean running = true;

    public Counter(int id,
                   String officeName,
                   BlockingQueue<Request> queue,
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

    // Sends counter on coffee break.
    public void sendOnBreak() {
        onBreak.set(true);
        Log.info("[%s-Counter-%d] Going on coffee break", officeName, id);
    }

    // Resumes counter from coffee break.
    public void resumeFromBreak() {
        synchronized (pauseLock) {
            onBreak.set(false);
            pauseLock.notifyAll();
        }
        Log.info("[%s-Counter-%d] Returned from coffee break", officeName, id);
    }

    @Override
    public void run() {
        try {
            while (running) {
                // Check coffee break state before polling queue.
                checkBreak();

                Request request = queue.take();

                // Poison pill terminates counter worker.
                if (Office.isPoisonPill(request)) {
                    break;
                }

                // Re-check coffee break state after receiving request.
                checkBreak();

                serve(request);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void checkBreak() throws InterruptedException {
        if (onBreak.get()) {
            synchronized (pauseLock) {
                while (onBreak.get()) {
                    pauseLock.wait();
                }
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
        request.result().complete(doc);
    }
}
