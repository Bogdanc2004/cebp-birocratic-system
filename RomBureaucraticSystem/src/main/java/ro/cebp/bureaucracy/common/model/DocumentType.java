package ro.cebp.bureaucracy.common.model;

import java.util.List;

/**
 * A kind of document, resolved and validated: we know which office issues it
 * and which documents must be presented to obtain it.
 */
public record DocumentType(String name, String issuingOffice, List<String> requires) {

    public DocumentType {
        requires = List.copyOf(requires);
    }
}
