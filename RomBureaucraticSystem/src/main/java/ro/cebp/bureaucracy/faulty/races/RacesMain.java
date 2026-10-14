package ro.cebp.bureaucracy.faulty.races;

import ro.cebp.bureaucracy.common.Bureaucracy;
import ro.cebp.bureaucracy.common.CommandLine;
import ro.cebp.bureaucracy.common.ConfigLoader;
import ro.cebp.bureaucracy.common.Log;
import ro.cebp.bureaucracy.common.Report;
import ro.cebp.bureaucracy.common.SimulationResult;

/**
 * Runs the data-race version, possibly several times, and counts how many runs broke an invariant.
 * Usage: {@code RacesMain [--config <path>] [--runs <n>] [--quiet]}
 */
public final class RacesMain {

    static final String DEFAULT_CONFIG = "config/races.json";

    private RacesMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        CommandLine cli = CommandLine.parse(args);
        // With several runs, the event log would be unreadable: keep only the summaries.
        Log.setEnabled(!cli.quiet() && cli.runs() == 1);

        // races.json has a short timeout: clients lost by R1 would otherwise make every failed run very long.
        Bureaucracy bureaucracy = ConfigLoader.load(
                cli.config().equals(CommandLine.DEFAULT_CONFIG) ? DEFAULT_CONFIG : cli.config());
        int failed = 0;
        for (int run = 1; run <= cli.runs(); run++) {
            OfficeRegistry.reset();
            SimulationResult result = new RacesSimulation(bureaucracy).run();
            if (!result.ok()) {
                failed++;
            }
            if (cli.runs() == 1) {
                Report.print("RACES SIMULATION", result);
            } else {
                System.out.printf("Run %3d: %-6s %d violation(s), %d ms%n", run,
                        result.ok() ? "OK" : "FAILED", result.violations().size(), result.elapsed().toMillis());
            }
        }
        System.out.printf("%nRuns with broken invariants: %d / %d%n", failed, cli.runs());
    }
}
