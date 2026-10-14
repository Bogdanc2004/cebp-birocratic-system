package ro.cebp.bureaucracy.correct;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.cebp.bureaucracy.common.ConcurrentStatistics;
import ro.cebp.bureaucracy.common.IssuedLedger;
import ro.cebp.bureaucracy.common.Log;
import ro.cebp.bureaucracy.common.StatisticsSnapshot;
import ro.cebp.bureaucracy.common.model.Document;
import ro.cebp.bureaucracy.common.model.DocumentType;
import ro.cebp.bureaucracy.common.model.OfficeConfig;
import ro.cebp.bureaucracy.common.model.Range;
import ro.cebp.bureaucracy.common.model.SimulationConfig;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientTest {

    private static final SimulationConfig SIM = new SimulationConfig(1, new Range(1, 3), new Range(10, 20), 0, 5, null);
    private static final DocumentType BIRTH = new DocumentType("CertificatNastere", "Evidenta", List.of());
    private static final DocumentType ADDRESS = new DocumentType("AdeverintaDomiciliu", "Primarie", List.of("CertificatNastere"));
    private static final DocumentType ID = new DocumentType("CarteIdentitate", "Evidenta",
            List.of("CertificatNastere", "AdeverintaDomiciliu"));

    private final ConcurrentStatistics stats = new ConcurrentStatistics();
    private final IssuedLedger ledger = new IssuedLedger();
    private Office evidenta;
    private Office primarie;
    private Map<String, Office> officeByDocument;

    @BeforeEach
    void openOffices() {
        Log.setEnabled(false);
        evidenta = new Office(new OfficeConfig("Evidenta", 2, List.of(BIRTH.name(), ID.name())), SIM, stats, ledger);
        primarie = new Office(new OfficeConfig("Primarie", 1, List.of(ADDRESS.name())), SIM, stats, ledger);
        officeByDocument = Map.of(BIRTH.name(), evidenta, ADDRESS.name(), primarie, ID.name(), evidenta);
        evidenta.start();
        primarie.start();
    }

    @AfterEach
    void closeOffices() throws InterruptedException {
        evidenta.shutdown();
        primarie.shutdown();
        Log.setEnabled(true);
    }

    @Test
    void clientWalksItsPlanAndGetsEveryDocument() throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        new Client(7, List.of(BIRTH, ADDRESS, ID), officeByDocument, stats, done).run();

        assertEquals(0, done.getCount(), "the latch must be counted down");
        List<String> received = ledger.documents().stream()
                .filter(d -> d.ownerId() == 7).map(d -> d.type().name()).toList();
        assertEquals(List.of("CertificatNastere", "AdeverintaDomiciliu", "CarteIdentitate"), received);

        StatisticsSnapshot snapshot = stats.snapshot();
        assertEquals(1, snapshot.finishedClients());
        assertEquals(3, snapshot.waitSamples(), "one wait sample per document");
    }

    @Test
    void refusedClientStillCountsDown() throws InterruptedException {
        // The plan skips the birth certificate, so the counter refuses the ID card.
        CountDownLatch done = new CountDownLatch(1);
        new Client(1, List.of(ID), officeByDocument, stats, done).run();

        assertEquals(0, done.getCount());
        assertEquals(0, stats.snapshot().finishedClients());
        assertTrue(ledger.documents().isEmpty());
    }

    @Test
    void clientWithoutOfficeStillCountsDown() {
        CountDownLatch done = new CountDownLatch(1);
        new Client(1, List.of(BIRTH), Map.of(), stats, done).run();
        assertEquals(0, done.getCount());
    }

    @Test
    void interruptedClientGivesUpAndCountsDown() throws InterruptedException {
        // An office whose counters were never started: nobody will ever serve the client.
        Office closed = new Office(new OfficeConfig("Closed", 1, List.of(BIRTH.name())), SIM, stats, ledger);
        CountDownLatch done = new CountDownLatch(1);
        Thread client = Thread.ofVirtual().start(
                new Client(1, List.of(BIRTH), Map.of(BIRTH.name(), closed), stats, done));

        Thread.sleep(50);
        client.interrupt();
        assertTrue(done.await(1, TimeUnit.SECONDS), "an interrupted client must still count down");
        assertEquals(0, stats.snapshot().finishedClients());
    }

    @Test
    void manyConcurrentClientsAreAllServed() throws InterruptedException {
        int clients = 200;
        CountDownLatch done = new CountDownLatch(clients);
        for (int id = 0; id < clients; id++) {
            Thread.ofVirtual().start(new Client(id, List.of(BIRTH, ADDRESS, ID), officeByDocument, stats, done));
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));

        assertEquals(clients, stats.snapshot().finishedClients());
        assertEquals(3L * clients, ledger.documents().size());
        assertEquals(3L * clients, ledger.documents().stream().map(Document::serialNo).distinct().count());
    }
}
