package ro.cebp.bureaucracy.faulty.locks;

import org.junit.jupiter.api.Test;
import ro.cebp.bureaucracy.common.ConcurrentStatistics;
import ro.cebp.bureaucracy.common.IssuedLedger;
import ro.cebp.bureaucracy.common.model.Document;
import ro.cebp.bureaucracy.common.model.DocumentType;
import ro.cebp.bureaucracy.common.model.OfficeConfig;
import ro.cebp.bureaucracy.common.model.Range;
import ro.cebp.bureaucracy.common.model.SimulationConfig;
import ro.cebp.bureaucracy.correct.Request;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each test switches on one bug and measures its real symptom, next to a control run with the
 * bug switched off. A test passes when the bug shows up.
 */
class LockDemonstrationTest {

    private static final int COFFEE_MAX_MS = 150;
    private static final SimulationConfig SIMULATION = new SimulationConfig(
            5, new Range(5, 10), new Range(100, COFFEE_MAX_MS), 0.0, 5, null);
    private static final DocumentType DOC = new DocumentType("CertificatNastere", "Primarie", List.of());

    private static Office office(Set<LockBug> bugs) {
        return new Office(new OfficeConfig("Primarie", 2, List.of(DOC.name())), SIMULATION,
                new ConcurrentStatistics(), new IssuedLedger(), bugs);
    }

    private static CompletableFuture<Document> submit(Office office) {
        CompletableFuture<Document> result = new CompletableFuture<>();
        office.submit(new Request(1, DOC, List.of(), result));
        return result;
    }

    /** Ends counter 1's break while it is still "drinking its coffee", then puts counter 2 on break. */
    private static CompletableFuture<Document> reopenDuringCoffeeThenSubmit(Office office) throws InterruptedException {
        Counter first = office.counters().get(0);
        Counter second = office.counters().get(1);
        office.start();

        assertTrue(office.startBreak(first));
        Thread.sleep(50);              // counter 1 has noticed the break and is having its coffee
        office.endBreak(first);        // the reopen signal arrives during the coffee
        Thread.sleep(COFFEE_MAX_MS + 150); // coffee is over

        // Only counter 1 can serve now.
        assertTrue(office.startBreak(second));
        return submit(office);
    }

    @Test
    void lostWakeupLeavesAnOpenCounterAsleepForever() throws Exception {
        Office office = office(EnumSet.of(LockBug.LOST_WAKEUP));
        try {
            CompletableFuture<Document> result = reopenDuringCoffeeThenSubmit(office);

            Thread.sleep(400);
            Counter first = office.counters().get(0);
            assertFalse(first.isOnBreak(), "counter 1 is officially open");
            assertEquals(Thread.State.WAITING, office.threads().get(0).getState(),
                    "BUG [L2]: counter 1 should be waiting for a signal that already came");
            assertFalse(result.isDone(), "BUG [L2]: nobody serves the client although a counter is open");
        } finally {
            office.endBreak(office.counters().get(1));
            office.shutdown();
        }
    }

    @Test
    void withoutLostWakeupTheReopenedCounterServes() throws Exception {
        Office office = office(EnumSet.noneOf(LockBug.class));
        try {
            CompletableFuture<Document> result = reopenDuringCoffeeThenSubmit(office);
            result.get(1, TimeUnit.SECONDS); // served by counter 1
        } finally {
            office.endBreak(office.counters().get(1));
            office.shutdown();
        }
    }

    /** Time to serve one client while counter 1 is on a 300 ms break. */
    private static long serveTimeDuringBreak(Set<LockBug> bugs) throws Exception {
        Office office = office(bugs);
        Counter first = office.counters().get(0);
        try {
            office.start();
            assertTrue(office.startBreak(first));
            Thread.sleep(50); // counter 1 is now on its break

            Thread.ofPlatform().start(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                    return;
                }
                office.endBreak(first);
            });

            long start = System.nanoTime();
            submit(office).get(2, TimeUnit.SECONDS); // counter 2 is open and should serve it
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        } finally {
            office.endBreak(first);
            office.shutdown();
        }
    }

    @Test
    void breakHoldingArchiveBlocksTheOtherCounters() throws Exception {
        long withBug = serveTimeDuringBreak(EnumSet.of(LockBug.BREAK_HOLDING_ARCHIVE));
        long withoutBug = serveTimeDuringBreak(EnumSet.noneOf(LockBug.class));

        // Service takes 5–10 ms. With the bug, counter 2 waits for the archive until the break ends.
        assertTrue(withBug >= 200, "BUG [P2]: expected counter 2 to be blocked by the break, took " + withBug + " ms");
        assertTrue(withoutBug < 150, "without the bug counter 2 should serve at once, took " + withoutBug + " ms");
    }

    /** CPU time used by idle counter 1 over 300 ms with an empty queue. */
    private static long idleCpuMs(Set<LockBug> bugs) throws Exception {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        Office office = office(bugs);
        try {
            office.start();
            Thread.sleep(50);
            long id = office.threads().get(0).threadId();
            long before = mx.getThreadCpuTime(id);
            Thread.sleep(300);
            return TimeUnit.NANOSECONDS.toMillis(mx.getThreadCpuTime(id) - before);
        } finally {
            office.shutdown();
        }
    }

    @Test
    void busyWaitBurnsCpuWhileIdle() throws Exception {
        long withBug = idleCpuMs(EnumSet.of(LockBug.BUSY_WAIT));
        long withoutBug = idleCpuMs(EnumSet.noneOf(LockBug.class));

        assertTrue(withBug >= 100, "BUG [P2]: an idle spinning counter should burn CPU, used " + withBug + " ms");
        assertTrue(withoutBug < 50, "a blocked idle counter should use almost no CPU, used " + withoutBug + " ms");
    }
}
