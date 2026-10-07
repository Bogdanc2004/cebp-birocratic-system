package ro.cebp.bureaucracy.correct;

import ro.cebp.bureaucracy.common.Statistics;
import ro.cebp.bureaucracy.common.model.DocumentType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * OWNER: M4 (Clients &amp; shared resources). STUB: only the agreed signatures, no logic.
 *
 * <p>Expected behavior, for each document of {@code plan} in order:
 * build a {@link Request} with the documents obtained so far, submit it to
 * {@code officeByDocument.get(doc.name())}, block on the result, and call
 * {@code stats.recordWait(ms)} <b>exactly once per document</b> (the InvariantChecker
 * counts on that). After the last document, call {@code stats.clientFinished()}.
 * <b>Always</b> call {@code done.countDown()} in a {@code finally} block, or the
 * simulation will wait until its timeout.
 */
public final class Client implements Runnable {

    public Client(int id, List<DocumentType> plan, Map<String, Office> officeByDocument,
                  Statistics stats, CountDownLatch done) {
        // TODO M4
    }

    @Override
    public void run() {
        throw new UnsupportedOperationException("TODO M4: Client.run");
    }
}
