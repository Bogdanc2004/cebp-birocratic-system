package ro.cebp.bureaucracy.faulty.locks;

import ro.cebp.bureaucracy.common.CommandLine;
import ro.cebp.bureaucracy.common.ConfigLoader;
import ro.cebp.bureaucracy.common.Log;
import ro.cebp.bureaucracy.common.Report;
import ro.cebp.bureaucracy.common.SimulationResult;

import java.util.EnumSet;

/**
 * Entry point of the deadlock / livelock / performance version. Each problem is shown on its own.
 * Usage: {@code LocksMain <deadlock|livelock|perf|lostwakeup> [--config <path>] [--quiet]}
 */
public final class LocksMain {

    private LocksMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        CommandLine cli = CommandLine.parse(args);
        Log.setEnabled(!cli.quiet());
        boolean defaultConfig = cli.config().equals(CommandLine.DEFAULT_CONFIG);
        String mode = cli.mode() == null ? "" : cli.mode();
        switch (mode) {
            case "perf" -> Benchmark.run(defaultConfig ? Benchmark.DEFAULT_CONFIG : cli.config());
            case "lostwakeup" -> lostWakeup();
            case "deadlock" -> deadlock(defaultConfig ? "config/shared-resources.json" : cli.config());
            case "livelock" -> notYet("livelock (L)", "M4");
            default -> {
                System.err.println("Usage: LocksMain <deadlock|livelock|perf|lostwakeup> [--config <path>] [--quiet]");
                System.exit(2);
            }
        }
    }

    /** BUG [L2]: the scripted demo, first with the bug, then without it for comparison. */
    private static void lostWakeup() throws InterruptedException {
        LostWakeupDemo.run(true);
        LostWakeupDemo.run(false);
    }

    private static void deadlock(String config) throws InterruptedException {
        Log.info("Running deadlock demonstration with %s", config);
        SimulationResult result = new LocksSimulation(ConfigLoader.load(config),
                EnumSet.of(LockBug.DEADLOCK_ORDER)).run();
        Report.print("DEADLOCK [D] DEMONSTRATION", result);
    }

    private static void notYet(String scenario, String owner) {
        System.err.println("Scenario " + scenario + " is not implemented yet (owner: " + owner + ")");
        System.exit(2);
    }
}
