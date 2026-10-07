package ro.cebp.bureaucracy.faulty.locks;

import ro.cebp.bureaucracy.common.Bureaucracy;
import ro.cebp.bureaucracy.common.CommandLine;
import ro.cebp.bureaucracy.common.ConfigLoader;
import ro.cebp.bureaucracy.common.Log;
import ro.cebp.bureaucracy.common.SimulationResult;
import ro.cebp.bureaucracy.correct.Simulation;

/**
 * Runs the correct version and the global-lock version (BUG [P1]) on the same configuration
 * and compares their times. Both use the same seed, so the clients want the same documents.
 * Usage: {@code Benchmark [--config <path>]} (default {@code config/benchmark.json})
 */
public final class Benchmark {

    static final String DEFAULT_CONFIG = "config/benchmark.json";

    private Benchmark() {
    }

    public static void main(String[] args) throws InterruptedException {
        CommandLine cli = CommandLine.parse(args);
        run(cli.config().equals(CommandLine.DEFAULT_CONFIG) ? DEFAULT_CONFIG : cli.config());
    }

    static void run(String config) throws InterruptedException {
        Log.setEnabled(false);
        Bureaucracy bureaucracy = ConfigLoader.load(config);
        System.out.printf("Benchmark: %d clients, config %s%n", bureaucracy.simulation().clients(), config);

        SimulationResult correct = new Simulation(bureaucracy).run();
        print("Correct (fine-grained)", correct);

        SimulationResult global = new GlobalLockSimulation(bureaucracy).run();
        print("Global lock [P1]", global);

        double slowdown = (double) global.elapsed().toMillis() / Math.max(1, correct.elapsed().toMillis());
        System.out.printf("%nThe global lock is %.1fx slower%n", slowdown);
    }

    private static void print(String name, SimulationResult result) {
        System.out.printf("  %-24s %7d ms   avg wait %7.1f ms   %s%n", name, result.elapsed().toMillis(),
                result.statistics().averageWaitMs(), result.ok() ? "OK" : "FAILED (timeout or violations)");
    }
}
