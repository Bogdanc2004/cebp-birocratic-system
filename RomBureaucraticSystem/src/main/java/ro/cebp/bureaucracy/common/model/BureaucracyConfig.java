package ro.cebp.bureaucracy.common.model;

import java.util.List;

/** Root of the JSON configuration file, exactly as it is read from disk (not validated yet). */
public record BureaucracyConfig(
        List<OfficeConfig> offices,
        List<DocumentConfig> documents,
        SimulationConfig simulation) {
}
