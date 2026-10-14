package ro.cebp.bureaucracy.faulty.locks;

import ro.cebp.bureaucracy.common.ConcurrentStatistics;
import ro.cebp.bureaucracy.common.IssuedLedger;
import ro.cebp.bureaucracy.common.Log;
import ro.cebp.bureaucracy.common.model.Document;
import ro.cebp.bureaucracy.common.model.DocumentType;
import ro.cebp.bureaucracy.common.model.OfficeConfig;
import ro.cebp.bureaucracy.common.model.Range;
import ro.cebp.bureaucracy.common.model.SimulationConfig;
import ro.cebp.bureaucracy.correct.Request;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Step-by-step demonstration of BUG [L2] on one office with two counters.
 *
 * <p>Why a scripted demo and not the full simulation: in the full simulation the scheduler keeps
 * sending counters on break, and the "back from break" signal of a later break wakes the counter
 * that missed the earlier one. A later signal hides the lost wakeup, which is exactly what makes
 * this bug intermittent and hard to catch in real systems. Here the timing is fixed, so it
 * happens every time.
 */
final class LostWakeupDemo {

    private static final int COFFEE_MAX_MS = 150;
    private static final SimulationConfig SIMULATION = new SimulationConfig(
            5, new Range(5, 10), new Range(100, COFFEE_MAX_MS), 0, 5, null);
    private static final DocumentType DOC = new DocumentType("CertificatNastere", "Primarie", List.of());

    private LostWakeupDemo() {
    }

    static void run(boolean withBug) throws InterruptedException {
        Set<LockBug> bugs = withBug ? EnumSet.of(LockBug.LOST_WAKEUP) : EnumSet.noneOf(LockBug.class);
        System.out.println("\n========== LOST WAKEUP [L2] " + (withBug ? "WITH" : "WITHOUT") + " THE BUG ==========");
        Office office = new Office(new OfficeConfig("Primarie", 2, List.of(DOC.name())), SIMULATION,
                new ConcurrentStatistics(), new IssuedLedger(), bugs);
        Counter first = office.counters().get(0);
        Counter second = office.counters().get(1);
        office.start();
        try {
            Log.info("Step 1: counter 1 goes on a coffee break");
            office.startBreak(first);
            Thread.sleep(50);

            Log.info("Step 2: the supervisor calls counter 1 back while it is still drinking its coffee");
            office.endBreak(first);
            Thread.sleep(COFFEE_MAX_MS + 150);

            Thread firstThread = office.threads().get(0);
            Log.info("Step 3: counter 1 is officially %s, its thread is %s",
                    first.isOnBreak() ? "ON BREAK" : "OPEN", firstThread.getState());

            Log.info("Step 4: counter 2 goes on break; 5 clients arrive, only counter 1 can serve them");
            office.startBreak(second);
            List<CompletableFuture<Document>> results = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                CompletableFuture<Document> result = new CompletableFuture<>();
                office.submit(new Request(i, DOC, List.of(), result));
                results.add(result);
            }
            Thread.sleep(1000);

            long served = results.stream().filter(CompletableFuture::isDone).count();
            Log.info("Step 5: after 1 s, %d of 5 clients were served", served);
            System.out.println(served == 0
                    ? "RESULT: counter 1 missed its wake-up signal and sleeps forever although the office counts it as open."
                    : "RESULT: counter 1 woke up normally and served the clients.");
        } finally {
            office.endBreak(second);
            office.shutdown();
        }
    }
}
