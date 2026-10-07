package ro.cebp.bureaucracy.faulty.locks;

import ro.cebp.bureaucracy.common.CommandLine;

/**
 * Entry point of the deadlock / livelock / performance version. Each problem is shown on its own.
 * Usage: {@code LocksMain <deadlock|livelock|starvation|perf> [--config <path>]}
 */
public final class LocksMain {

    private LocksMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        CommandLine cli = CommandLine.parse(args);
        String mode = cli.mode() == null ? "" : cli.mode();
        switch (mode) {
            case "perf" -> Benchmark.run(cli.config().equals(CommandLine.DEFAULT_CONFIG)
                    ? Benchmark.DEFAULT_CONFIG : cli.config());
            case "deadlock" -> notYet("deadlock (D1, D2)", "M4");
            case "livelock" -> notYet("livelock (L1)", "M3");
            case "starvation" -> notYet("starvation (S1)", "M3");
            default -> {
                System.err.println("Usage: LocksMain <deadlock|livelock|starvation|perf> [--config <path>]");
                System.exit(2);
            }
        }
    }

    // TODO: replace with the real scenarios when M3 and M4 add them.
    private static void notYet(String scenario, String owner) {
        System.err.println("Scenario " + scenario + " is not implemented yet (owner: " + owner + ")");
        System.exit(2);
    }
}
