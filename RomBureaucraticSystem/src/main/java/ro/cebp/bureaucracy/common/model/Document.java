package ro.cebp.bureaucracy.common.model;

/**
 * A concrete document issued to a client. Immutable, so it can be handed
 * from a counter thread to a client thread without any synchronization.
 *
 * @param serialNo must be unique across the whole system (checked by the InvariantChecker)
 */
public record Document(DocumentType type, int ownerId, long serialNo) {
}
