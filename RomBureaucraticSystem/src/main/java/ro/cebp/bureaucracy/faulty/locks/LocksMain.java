package ro.cebp.bureaucracy.faulty.locks;

import ro.cebp.bureaucracy.common.CommandLine;

/**
 * Entry point of the deadlock / livelock / performance version. Each problem is shown on its own.
 * Usage: {@code LocksMain <deadlock|livelock|perf|lostwakeup> [--config <path>] [--quiet]}
 */
public final class LocksMain {

    private LocksMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        CommandLine cli = CommandLine.parse(args);
        boolean defaultConfig = cli.config().equals(CommandLine.DEFAULT_CONFIG);
        String mode = cli.mode() == null ? "" : cli.mode();
        switch (mode) {
            case "perf" -> Benchmark.run(defaultConfig ? Benchmark.DEFAULT_CONFIG : cli.config());
            case "lostwakeup" -> lostWakeup();
            case "deadlock" -> notYet("deadlock (D)", "M3");
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

    // TODO: replace with the real scenarios when M3 and M4 add them.
    private static void notYet(String scenario, String owner) {
        System.err.println("Scenario " + scenario + " is not implemented yet (owner: " + owner + ")");
        System.exit(2);
    }
}
