package ro.cebp.bureaucracy.common.model;

import java.util.List;

/**
 * A document type as described in the JSON configuration.
 *
 * @param uses shared resources a counter needs while issuing this document, in the order the
 *             office's procedure lists them (the faulty-locks version acquires them in exactly this order)
 */
public record DocumentConfig(String name, List<String> requires, List<String> uses) {

    public DocumentConfig {
        requires = requires == null ? List.of() : List.copyOf(requires);
        uses = uses == null ? List.of() : List.copyOf(uses);
    }

    public DocumentConfig(String name, List<String> requires) {
        this(name, requires, List.of());
    }
}
