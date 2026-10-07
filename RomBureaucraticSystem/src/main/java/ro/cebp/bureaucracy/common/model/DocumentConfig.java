package ro.cebp.bureaucracy.common.model;

import java.util.List;

/** A document type as described in the JSON configuration. */
public record DocumentConfig(String name, List<String> requires) {

    public DocumentConfig {
        requires = requires == null ? List.of() : List.copyOf(requires);
    }
}
