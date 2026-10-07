package ro.cebp.bureaucracy.common.model;

/**
 * Simulation parameters from the JSON configuration.
 *
 * @param clients           number of client threads to launch
 * @param serviceTimeMs     how long a counter works on one request
 * @param coffeeBreakMs     how long a coffee break lasts
 * @param coffeeBreakChance probability (0..1) that a counter goes on break at each scheduler tick
 * @param timeoutSeconds    the simulation is declared stuck if clients have not finished by then
 * @param seed              optional seed for choosing client targets (null = random every run)
 */
public record SimulationConfig(
        int clients,
        Range serviceTimeMs,
        Range coffeeBreakMs,
        double coffeeBreakChance,
        int timeoutSeconds,
        Long seed) {
}
