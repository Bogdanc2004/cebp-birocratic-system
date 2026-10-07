package ro.cebp.bureaucracy.common;

import ro.cebp.bureaucracy.common.model.DocumentType;
import ro.cebp.bureaucracy.common.model.OfficeConfig;
import ro.cebp.bureaucracy.common.model.SimulationConfig;

import java.util.List;
import java.util.Map;

/**
 * A validated configuration, ready to be simulated. Produced only by {@link ConfigLoader}.
 * Immutable, so it is shared freely between threads.
 *
 * @param documents document name -> resolved document type
 */
public record Bureaucracy(
        List<OfficeConfig> offices,
        Map<String, DocumentType> documents,
        DependencyGraph graph,
        SimulationConfig simulation) {

    public Bureaucracy {
        offices = List.copyOf(offices);
        documents = Map.copyOf(documents);
    }
}
