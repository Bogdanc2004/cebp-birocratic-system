package ro.cebp.bureaucracy.common;

import java.util.Map;

/** Prints the end-of-run summary. */
public final class Report {

    private static final int MAX_VIOLATIONS_SHOWN = 20;

    private Report() {
    }

    public static void print(String title, SimulationResult result) {
        StatisticsSnapshot stats = result.statistics();
        StringBuilder out = new StringBuilder();
        out.append("\n========== ").append(title).append(" ==========\n");
        out.append(String.format("Elapsed:            %d ms%n", result.elapsed().toMillis()));
        out.append(String.format("Finished in time:   %s%n", result.finishedInTime() ? "yes" : "NO (timeout)"));
        out.append(String.format("Clients finished:   %d%n", stats.finishedClients()));
        out.append(String.format("Documents issued:   %d%n", stats.totalIssued()));
        for (Map.Entry<String, Long> office : stats.issuedPerOffice().entrySet()) {
            out.append(String.format("  %-28s %d%n", office.getKey(), office.getValue()));
        }
        out.append(String.format("Average wait:       %.1f ms%n", stats.averageWaitMs()));
        out.append(String.format("Maximum wait:       %d ms%n", stats.maxWaitMs()));

        if (result.violations().isEmpty()) {
            out.append("Invariants:         all hold\n");
        } else {
            out.append("Invariants:         ").append(result.violations().size()).append(" VIOLATED\n");
            result.violations().stream().limit(MAX_VIOLATIONS_SHOWN)
                    .forEach(v -> out.append("  - ").append(v).append('\n'));
            if (result.violations().size() > MAX_VIOLATIONS_SHOWN) {
                out.append("  ... and ").append(result.violations().size() - MAX_VIOLATIONS_SHOWN).append(" more\n");
            }
        }
        out.append("Status:             ").append(result.ok() ? "OK" : "FAILED").append('\n');
        System.out.print(out);
    }
}
