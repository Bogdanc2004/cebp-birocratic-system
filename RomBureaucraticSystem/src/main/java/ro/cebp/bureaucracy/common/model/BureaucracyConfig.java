package ro.cebp.bureaucracy.common.model;

import java.util.List;

/**
 * Root of the JSON configuration file, exactly as it is read from disk (not validated yet).
 *
 * @param resources optional: shared resources (stamp, register...); their order is the
 *                  institution's global acquisition order
 */
public record BureaucracyConfig(
        List<OfficeConfig> offices,
        List<DocumentConfig> documents,
        List<ResourceConfig> resources,
        SimulationConfig simulation) {

    public BureaucracyConfig {
        resources = resources == null ? List.of() : List.copyOf(resources);
    }

    public BureaucracyConfig(List<OfficeConfig> offices, List<DocumentConfig> documents, SimulationConfig simulation) {
        this(offices, documents, List.of(), simulation);
    }
}
