package ro.cebp.bureaucracy.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import ro.cebp.bureaucracy.common.model.BureaucracyConfig;
import ro.cebp.bureaucracy.common.model.DocumentConfig;
import ro.cebp.bureaucracy.common.model.DocumentType;
import ro.cebp.bureaucracy.common.model.OfficeConfig;
import ro.cebp.bureaucracy.common.model.Range;
import ro.cebp.bureaucracy.common.model.ResourceConfig;
import ro.cebp.bureaucracy.common.model.SimulationConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Reads the JSON configuration and turns it into a validated {@link Bureaucracy}.
 * All validation happens here, before any thread starts, so the simulation code
 * can assume the configuration is consistent.
 */
public final class ConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ConfigLoader() {
    }

    /** Loads from a file path if it exists, otherwise from the classpath (e.g. {@code config/default.json}). */
    public static Bureaucracy load(String location) {
        Path path = Path.of(location);
        if (Files.isRegularFile(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                return parse(in, location);
            } catch (IOException e) {
                throw new ConfigException("Cannot read config file " + location, e);
            }
        }
        try (InputStream in = ConfigLoader.class.getClassLoader().getResourceAsStream(location)) {
            if (in == null) {
                throw new ConfigException("Config not found (neither file nor classpath resource): " + location);
            }
            return parse(in, location);
        } catch (IOException e) {
            throw new ConfigException("Cannot read config resource " + location, e);
        }
    }

    private static Bureaucracy parse(InputStream in, String location) {
        BureaucracyConfig config;
        try {
            config = MAPPER.readValue(in, BureaucracyConfig.class);
        } catch (IOException e) {
            throw new ConfigException("Malformed JSON in " + location + ": " + e.getMessage(), e);
        }
        return validate(config);
    }

    /** Checks the raw configuration and resolves it into a {@link Bureaucracy}. */
    public static Bureaucracy validate(BureaucracyConfig config) {
        if (config.offices() == null || config.offices().isEmpty()) {
            throw new ConfigException("At least one office is required");
        }
        if (config.documents() == null || config.documents().isEmpty()) {
            throw new ConfigException("At least one document is required");
        }
        validateSimulation(config.simulation());

        // document name -> office that issues it
        Map<String, String> issuer = new HashMap<>();
        Set<String> officeNames = new HashSet<>();
        for (OfficeConfig office : config.offices()) {
            requireName(office.name(), "office");
            if (!officeNames.add(office.name())) {
                throw new ConfigException("Duplicate office: " + office.name());
            }
            if (office.counters() < 1) {
                throw new ConfigException("Office '" + office.name() + "' must have at least one counter");
            }
            for (String doc : office.issues()) {
                String previous = issuer.putIfAbsent(doc, office.name());
                if (previous != null) {
                    throw new ConfigException("Document '" + doc + "' is issued by two offices: '"
                            + previous + "' and '" + office.name() + "'");
                }
            }
        }

        Set<String> resourceNames = new HashSet<>();
        for (ResourceConfig resource : config.resources()) {
            requireName(resource.name(), "resource");
            if (!resourceNames.add(resource.name())) {
                throw new ConfigException("Duplicate resource: " + resource.name());
            }
            if (resource.permits() < 1) {
                throw new ConfigException("Resource '" + resource.name() + "' must have at least one permit");
            }
        }

        Map<String, DocumentType> documents = new HashMap<>();
        for (DocumentConfig doc : config.documents()) {
            requireName(doc.name(), "document");
            String office = issuer.get(doc.name());
            if (office == null) {
                throw new ConfigException("No office issues document '" + doc.name() + "'");
            }
            Set<String> used = new HashSet<>();
            for (String resource : doc.uses()) {
                if (!resourceNames.contains(resource)) {
                    throw new ConfigException("Document '" + doc.name() + "' uses unknown resource '" + resource + "'");
                }
                if (!used.add(resource)) {
                    throw new ConfigException("Document '" + doc.name() + "' lists resource '" + resource + "' twice");
                }
            }
            if (documents.put(doc.name(), new DocumentType(doc.name(), office, doc.requires(), doc.uses())) != null) {
                throw new ConfigException("Duplicate document: " + doc.name());
            }
        }

        for (String doc : issuer.keySet()) {
            if (!documents.containsKey(doc)) {
                throw new ConfigException("Office '" + issuer.get(doc) + "' issues undeclared document '" + doc + "'");
            }
        }

        // Detects unknown requirements and dependency cycles.
        DependencyGraph graph = new DependencyGraph(documents);
        return new Bureaucracy(config.offices(), documents, config.resources(), graph, config.simulation());
    }

    private static void validateSimulation(SimulationConfig sim) {
        if (sim == null) {
            throw new ConfigException("The 'simulation' section is required");
        }
        if (sim.clients() < 1) {
            throw new ConfigException("simulation.clients must be at least 1");
        }
        validateRange(sim.serviceTimeMs(), "simulation.serviceTimeMs");
        validateRange(sim.coffeeBreakMs(), "simulation.coffeeBreakMs");
        if (sim.coffeeBreakChance() < 0 || sim.coffeeBreakChance() > 1) {
            throw new ConfigException("simulation.coffeeBreakChance must be between 0 and 1");
        }
        if (sim.timeoutSeconds() < 1) {
            throw new ConfigException("simulation.timeoutSeconds must be at least 1");
        }
    }

    private static void validateRange(Range range, String field) {
        if (range == null) {
            throw new ConfigException(field + " is required");
        }
        if (range.min() < 0 || range.max() < range.min()) {
            throw new ConfigException(field + " must satisfy 0 <= min <= max");
        }
    }

    private static void requireName(String name, String what) {
        if (name == null || name.isBlank()) {
            throw new ConfigException("Every " + what + " needs a non-empty name");
        }
    }
}
