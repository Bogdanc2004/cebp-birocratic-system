package ro.cebp.bureaucracy.correct;

import org.junit.jupiter.api.Test;
import ro.cebp.bureaucracy.common.ConcurrentStatistics;
import ro.cebp.bureaucracy.common.IssuedLedger;
import ro.cebp.bureaucracy.common.model.Document;
import ro.cebp.bureaucracy.common.model.DocumentType;
import ro.cebp.bureaucracy.common.model.OfficeConfig;
import ro.cebp.bureaucracy.common.model.Range;
import ro.cebp.bureaucracy.common.model.SimulationConfig;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficeTest {

    private final SimulationConfig fastSimulation = new SimulationConfig(
            10,
            new Range(0, 5),
            new Range(10, 20),
            0.0,
            10,
            null
    );

    @Test
    void processesRequestsAndRecordsLedgerAndStats() throws Exception {
        OfficeConfig config = new OfficeConfig("Primarie", 3, List.of("CertificatNastere"));
        ConcurrentStatistics stats = new ConcurrentStatistics();
        IssuedLedger ledger = new IssuedLedger();
        Office office = new Office(config, fastSimulation, stats, ledger);

        DocumentType docType = new DocumentType("CertificatNastere", "Primarie", List.of());
        int requestCount = 50;
        List<CompletableFuture<Document>> futures = new ArrayList<>();

        office.start();
        for (int i = 0; i < requestCount; i++) {
            CompletableFuture<Document> future = new CompletableFuture<>();
            futures.add(future);
            office.submit(new Request(i, docType, List.of(), future));
        }

        // Wait for all requests to finish and collect issued documents.
        Set<Long> serials = new HashSet<>();
        for (int i = 0; i < requestCount; i++) {
            Document doc = futures.get(i).get();
            assertEquals("CertificatNastere", doc.type().name());
            assertEquals(i, doc.ownerId());
            serials.add(doc.serialNo());
        }

        office.shutdown();

        // Verify serial numbers uniqueness and statistics tracking.
        assertEquals(requestCount, serials.size());
        assertEquals(requestCount, ledger.documents().size());
        assertEquals(requestCount, stats.snapshot().totalIssued());
        assertEquals(requestCount, stats.snapshot().issuedPerOffice().get("Primarie"));
    }

    @Test
    void failsWhenPrerequisiteIsMissing() throws Exception {
        OfficeConfig config = new OfficeConfig("Evidenta", 1, List.of("CarteIdentitate"));
        ConcurrentStatistics stats = new ConcurrentStatistics();
        IssuedLedger ledger = new IssuedLedger();
        Office office = new Office(config, fastSimulation, stats, ledger);

        DocumentType docType = new DocumentType("CarteIdentitate", "Evidenta", List.of("CertificatNastere"));
        CompletableFuture<Document> future = new CompletableFuture<>();

        office.start();
        // Submit request without presenting required CertificatNastere.
        office.submit(new Request(1, docType, List.of(), future));

        ExecutionException exception = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalStateException.class, exception.getCause());
        assertTrue(exception.getCause().getMessage().contains("missing prerequisite"));

        office.shutdown();
    }
}
