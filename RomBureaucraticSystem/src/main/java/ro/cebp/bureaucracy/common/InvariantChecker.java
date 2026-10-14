package ro.cebp.bureaucracy.common;

import ro.cebp.bureaucracy.common.model.Document;
import ro.cebp.bureaucracy.common.model.DocumentType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Checks after a run that the shared state is consistent. If the correct version
 * reports a violation, there is a bug. The faulty versions should report some.
 *
 * <p>Call it only after all threads have stopped. Otherwise the numbers may still be moving.
 */
public final class InvariantChecker {

    private InvariantChecker() {
    }

    /**
     * @param plans    client id -> documents that client had to obtain (its plan)
     * @param issued   everything that was actually issued ({@link IssuedLedger#documents()})
     * @param snapshot the statistics as counted by the counters and clients
     * @return human-readable violations; empty means everything is consistent
     */
    public static List<String> check(Map<Integer, List<DocumentType>> plans,
                                     List<Document> issued,
                                     StatisticsSnapshot snapshot) {
        List<String> violations = new ArrayList<>();

        // 1. Exactly as many documents issued as clients needed.
        long expectedTotal = plans.values().stream().mapToLong(List::size).sum();
        if (issued.size() != expectedTotal) {
            violations.add("Issued documents: expected " + expectedTotal + ", actual " + issued.size());
        }

        // 2. Serial numbers are unique.
        Set<Long> serials = new HashSet<>();
        for (Document doc : issued) {
            if (!serials.add(doc.serialNo())) {
                violations.add("Duplicate serial number " + doc.serialNo() + " (" + doc.type().name()
                        + " for client " + doc.ownerId() + ")");
            }
        }

        // 3. Every client got exactly the documents of its plan: none missing, none twice.
        Map<Integer, Map<String, Integer>> received = new HashMap<>();
        for (Document doc : issued) {
            received.computeIfAbsent(doc.ownerId(), k -> new HashMap<>())
                    .merge(doc.type().name(), 1, Integer::sum);
        }
        for (Map.Entry<Integer, List<DocumentType>> entry : plans.entrySet()) {
            int client = entry.getKey();
            Map<String, Integer> got = received.getOrDefault(client, Map.of());
            for (DocumentType needed : entry.getValue()) {
                int count = got.getOrDefault(needed.name(), 0);
                if (count == 0) {
                    violations.add("Client " + client + " never received " + needed.name());
                } else if (count > 1) {
                    violations.add("Client " + client + " received " + needed.name() + " " + count + " times");
                }
            }
        }
        for (Integer owner : received.keySet()) {
            if (!plans.containsKey(owner)) {
                violations.add("Documents issued to unknown client " + owner);
            }
        }

        // 4. All clients finished.
        if (snapshot.finishedClients() != plans.size()) {
            violations.add("Finished clients: expected " + plans.size() + ", actual " + snapshot.finishedClients());
        }

        // 5. The statistics agree with the ledger, office by office.
        Map<String, Long> ledgerPerOffice = new TreeMap<>();
        for (Document doc : issued) {
            ledgerPerOffice.merge(doc.type().issuingOffice(), 1L, Long::sum);
        }
        Set<String> offices = new HashSet<>(ledgerPerOffice.keySet());
        offices.addAll(snapshot.issuedPerOffice().keySet());
        for (String office : offices) {
            long inLedger = ledgerPerOffice.getOrDefault(office, 0L);
            long inStats = snapshot.issuedPerOffice().getOrDefault(office, 0L);
            if (inLedger != inStats) {
                violations.add("Office '" + office + "': statistics say " + inStats
                        + " issued, ledger says " + inLedger + " (lost updates?)");
            }
        }

        // 6. One wait sample per document obtained.
        if (snapshot.waitSamples() != issued.size()) {
            violations.add("Wait samples: expected " + issued.size() + ", actual " + snapshot.waitSamples());
        }

        return violations;
    }

    /**
     * Coffee-break rule: an office must never have had all its counters closed.
     *
     * @param minOpenCounters office name -> lowest number of open counters reached during the run
     */
    public static List<String> checkOffices(Map<String, Integer> minOpenCounters) {
        List<String> violations = new ArrayList<>();
        new TreeMap<>(minOpenCounters).forEach((office, min) -> {
            if (min < 1) {
                violations.add("Office '" + office + "' had " + min + " open counters at some point");
            }
        });
        return violations;
    }
}
