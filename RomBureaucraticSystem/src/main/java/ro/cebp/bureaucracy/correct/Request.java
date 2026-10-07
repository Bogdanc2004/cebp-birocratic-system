package ro.cebp.bureaucracy.correct;

import ro.cebp.bureaucracy.common.model.Document;
import ro.cebp.bureaucracy.common.model.DocumentType;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * OWNER: M2 (Queues &amp; Counters). This is the agreed contract; M2 may add fields.
 *
 * <p>A client asks an office for one document. The client blocks on {@code result.get()},
 * and the counter that serves the request calls {@code result.complete(document)}.
 * {@code complete} happens-before the {@code get} that returns, so the client always sees
 * the fully built document.
 *
 * @param presented the documents the client already holds, which the counter checks
 */
public record Request(
        int clientId,
        DocumentType wanted,
        List<Document> presented,
        CompletableFuture<Document> result) {

    public Request {
        presented = List.copyOf(presented);
    }
}
