package ro.cebp.bureaucracy.faulty.races;

import ro.cebp.bureaucracy.common.IssuedLedger;
import ro.cebp.bureaucracy.common.Log;
import ro.cebp.bureaucracy.common.Statistics;
import ro.cebp.bureaucracy.common.model.Document;
import ro.cebp.bureaucracy.common.model.DocumentType;
import ro.cebp.bureaucracy.common.model.SimulationConfig;
import ro.cebp.bureaucracy.correct.Request;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

// Worker counter thread with intentional race conditions (BUG R1 and BUG R3).
public final class Counter implements Runnable {

    // BUG [R3]: Plain static counter without synchronization causes duplicate serial numbers.
    private static long nextSerial = 1;

    private final int id;
    private final String officeName;
    private final List<Request> queue;
    private final SimulationConfig simulation;
    private final Statistics stats;
    private final IssuedLedger ledger;

    private final AtomicBoolean onBreak = new AtomicBoolean(false);
    private final Object pauseLock = new Object();
    private volatile boolean running = true;

    public Counter(int id,
                   String officeName,
                   List<Request> queue,
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

    // Resets serial number counter across runs.
    public static void resetSerial() {
        nextSerial = 1;
    }

    public int id() {
        return id;
    }

    public boolean isOnBreak() {
        return onBreak.get();
    }

    // Sends counter on coffee break (same as the correct version). Only Office.startBreak calls it.
    boolean goOnBreak() {
        if (!onBreak.compareAndSet(false, true)) {
            return false;
        }
        Log.info("[%s-Counter-%d] Going on coffee break", officeName, id);
        return true;
    }

    // Resumes counter from coffee break (same as the correct version). Only Office.endBreak calls it.
    boolean returnFromBreak() {
        synchronized (pauseLock) {
            if (!onBreak.compareAndSet(true, false)) {
                return false;
            }
            pauseLock.notifyAll();
        }
        Log.info("[%s-Counter-%d] Returned from coffee break", officeName, id);
        return true;
    }

    // Stops the worker loop. Safety net for shutdown: the R1 race can lose or swallow the poison pills.
    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        try {
            while (running) {
                checkBreak();

                Request request = pollRequest();
                if (request == null) {
                    // Back off briefly when queue is empty to avoid burning full CPU.
                    Thread.sleep(5);
                    continue;
                }

                // Check for poison pill indicating shutdown.
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

    // BUG [R1]: Check-then-act on plain ArrayList without synchronization leads to race conditions.
    private Request pollRequest() {
        try {
            if (!queue.isEmpty()) {
                paperwork();
                if (!queue.isEmpty()) {
                    return queue.remove(0);
                }
            }
        } catch (IndexOutOfBoundsException e) {
            // Concurrent remove by another counter empty chair symptom.
            Log.info("[%s-Counter-%d] Race on queue: empty chair reached", officeName, id);
        }
        return null;
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

        // BUG [R3]: Read-modify-write on nextSerial without synchronization generates duplicate serial numbers.
        long serial = generateSerial();

        Document doc = new Document(wanted, request.clientId(), serial);
        ledger.record(doc);
        stats.recordIssued(officeName);
        request.result().complete(doc);
    }

    // BUG [R3]: Unsynchronized read-modify-write causes duplicate serial numbers across concurrent threads.
    static long generateSerial() {
        long current = nextSerial;
        paperwork();
        nextSerial = current + 1;
        return current;
    }

    // Artificial pause to widen the race window so bugs show up consistently.
    private static void paperwork() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
