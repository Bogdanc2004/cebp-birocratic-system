package ro.cebp.bureaucracy.common;

import org.junit.jupiter.api.Test;
import ro.cebp.bureaucracy.common.model.Document;
import ro.cebp.bureaucracy.common.model.DocumentType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvariantCheckerTest {

    private static final DocumentType A = new DocumentType("A", "P", List.of());
    private static final DocumentType B = new DocumentType("B", "Q", List.of("A"));

    // client 0 needs A then B, client 1 needs only A
    private static final Map<Integer, List<DocumentType>> PLANS = Map.of(0, List.of(A, B), 1, List.of(A));

    private static StatisticsSnapshot stats(long p, long q, long waits, int finished) {
        return new StatisticsSnapshot(Map.of("P", p, "Q", q), waits, 0, 0, finished);
    }

    private static boolean mentions(List<String> violations, String text) {
        return violations.stream().anyMatch(v -> v.contains(text));
    }

    @Test
    void consistentRunHasNoViolations() {
        List<Document> issued = List.of(new Document(A, 0, 1), new Document(B, 0, 2), new Document(A, 1, 3));
        assertEquals(List.of(), InvariantChecker.check(PLANS, issued, stats(2, 1, 3, 2)));
    }

    @Test
    void duplicateSerialIsDetected() {
        List<Document> issued = List.of(new Document(A, 0, 1), new Document(B, 0, 1), new Document(A, 1, 3));
        List<String> v = InvariantChecker.check(PLANS, issued, stats(2, 1, 3, 2));
        assertTrue(mentions(v, "Duplicate serial number 1"), v.toString());
    }

    @Test
    void missingAndDoubleDocumentsAreDetected() {
        // client 0 got A twice and never got B
        List<Document> issued = List.of(new Document(A, 0, 1), new Document(A, 0, 2), new Document(A, 1, 3));
        List<String> v = InvariantChecker.check(PLANS, issued, stats(3, 0, 3, 2));
        assertTrue(mentions(v, "Client 0 never received B"), v.toString());
        assertTrue(mentions(v, "Client 0 received A 2 times"), v.toString());
    }

    @Test
    void lostStatisticUpdatesAreDetected() {
        List<Document> issued = List.of(new Document(A, 0, 1), new Document(B, 0, 2), new Document(A, 1, 3));
        // office P really issued 2, but its counter says 1; one client never counted as finished
        List<String> v = InvariantChecker.check(PLANS, issued, stats(1, 1, 3, 1));
        assertTrue(mentions(v, "Office 'P'"), v.toString());
        assertTrue(mentions(v, "Finished clients"), v.toString());
    }

    @Test
    void wrongTotalIsDetected() {
        List<Document> issued = List.of(new Document(A, 0, 1), new Document(A, 1, 3));
        List<String> v = InvariantChecker.check(PLANS, issued, stats(2, 0, 2, 2));
        assertTrue(mentions(v, "Issued documents: expected 3, actual 2"), v.toString());
    }
}
