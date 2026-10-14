package ro.cebp.bureaucracy.common.model;

import java.util.List;

/**
 * A kind of document, resolved and validated: we know which office issues it,
 * which documents must be presented to obtain it, and which shared resources
 * (stamp, register...) the counter needs while issuing it.
 */
public record DocumentType(String name, String issuingOffice, List<String> requires, List<String> uses) {

    public DocumentType {
        requires = List.copyOf(requires);
        uses = List.copyOf(uses);
    }

    public DocumentType(String name, String issuingOffice, List<String> requires) {
        this(name, issuingOffice, requires, List.of());
    }
}
