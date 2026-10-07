package ro.cebp.bureaucracy.common.model;

import java.util.List;

/** An office as described in the JSON configuration. */
public record OfficeConfig(String name, int counters, List<String> issues) {

    public OfficeConfig {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
