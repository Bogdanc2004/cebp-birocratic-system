package ro.cebp.bureaucracy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ro.cebp.bureaucracy.common.Bureaucracy;
import ro.cebp.bureaucracy.common.ConfigLoader;
import ro.cebp.bureaucracy.common.Log;
import ro.cebp.bureaucracy.common.SimulationResult;
import ro.cebp.bureaucracy.correct.Simulation;
import ro.cebp.bureaucracy.faulty.locks.GlobalLockSimulation;
import ro.cebp.bureaucracy.faulty.races.RacesSimulation;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whole simulations, start to finish. */
class EndToEndTest {

    @BeforeEach
    void quiet() {
        Log.setEnabled(false);
    }

    @AfterEach
    void loud() {
        Log.setEnabled(true);
    }

    @Test
    void correctSimulationKeepsEveryInvariantRunAfterRun() throws InterruptedException {
        Bureaucracy bureaucracy = ConfigLoader.load("config/small.json");
        for (int run = 0; run < 5; run++) {
            SimulationResult result = new Simulation(bureaucracy).run();
            assertTrue(result.ok(), "run " + run + ": " + result.violations());
        }
    }

    @Test
    void correctSimulationSurvivesManyClientsAndFrequentBreaks() throws InterruptedException {
        // races.json: 100 clients, 8 counters, short service times.
        SimulationResult result = new Simulation(ConfigLoader.load("config/races.json")).run();
        assertTrue(result.ok(), result.violations().toString());
    }

    @Test
    void globalLockIsCorrectJustSlow() throws InterruptedException {
        SimulationResult result = new GlobalLockSimulation(ConfigLoader.load("config/small.json")).run();
        assertTrue(result.ok(), "P1 must not corrupt anything: " + result.violations());
    }

    @Test
    void racesVersionBreaksInvariantsAndStillEnds() throws InterruptedException {
        // The run must END (no hang) and report the corruption.
        SimulationResult result = new RacesSimulation(ConfigLoader.load("config/races.json")).run();
        assertTrue(!result.ok(), "expected the races version to break some invariant");
    }
}
