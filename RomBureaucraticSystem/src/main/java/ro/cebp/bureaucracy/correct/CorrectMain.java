package ro.cebp.bureaucracy.correct;

import ro.cebp.bureaucracy.common.Bureaucracy;
import ro.cebp.bureaucracy.common.CommandLine;
import ro.cebp.bureaucracy.common.ConfigLoader;
import ro.cebp.bureaucracy.common.Log;
import ro.cebp.bureaucracy.common.Report;
import ro.cebp.bureaucracy.common.SimulationResult;

/**
 * Runs the correct (thread-safe) simulation.
 * Usage: {@code CorrectMain [--config <path>] [--quiet]}
 */
public final class CorrectMain {

    private CorrectMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        CommandLine cli = CommandLine.parse(args);
        Log.setEnabled(!cli.quiet());

        Bureaucracy bureaucracy = ConfigLoader.load(cli.config());
        SimulationResult result = new Simulation(bureaucracy).run();

        Report.print("CORRECT SIMULATION", result);
        System.exit(result.ok() ? 0 : 1);
    }
}
