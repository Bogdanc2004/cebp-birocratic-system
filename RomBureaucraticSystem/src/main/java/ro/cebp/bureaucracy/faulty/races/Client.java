package ro.cebp.bureaucracy.faulty.races;

// Copy of correct.Client with no bugs of its own: the bugs of this program live in Office/Counter.

import ro.cebp.bureaucracy.common.Log;
import ro.cebp.bureaucracy.common.Statistics;
import ro.cebp.bureaucracy.common.model.Document;
import ro.cebp.bureaucracy.common.model.DocumentType;
import ro.cebp.bureaucracy.correct.Request;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * A citizen, simulated by one (virtual) thread. Walks its plan in order: for every document it
 * joins the queue of the office that issues it, shows the documents it already holds, and waits
 * until a counter hands over the new one.
 *
 * <p>Thread safety:
 * <ul>
 *   <li>The portfolio (documents obtained so far) is <b>thread-confined</b>: only this client's
 *       thread ever touches it. Counters never see the list itself, only the immutable copy
 *       made by {@link Request} at submission time.</li>
 *   <li>The plan and the office map are read-only, built before the thread started.</li>
 *   <li>{@code result.get()} blocks without burning CPU. A virtual thread parked there does not
 *       hold a carrier thread, so thousands of waiting clients are cheap.</li>
 * </ul>
 */
public final class Client implements Runnable {

    private final int id;
    private final List<DocumentType> plan;
    private final Map<String, Office> officeByDocument;
    private final Statistics stats;
    private final CountDownLatch done;

    public Client(int id, List<DocumentType> plan, Map<String, Office> officeByDocument,
                  Statistics stats, CountDownLatch done) {
        this.id = id;
        this.plan = List.copyOf(plan);
        this.officeByDocument = officeByDocument;
        this.stats = stats;
        this.done = done;
    }

    @Override
    public void run() {
        List<Document> portfolio = new ArrayList<>(); // thread-confined
        try {
            for (DocumentType wanted : plan) {
                Office office = officeByDocument.get(wanted.name());
                if (office == null) {
                    throw new IllegalStateException("no office issues " + wanted.name());
                }

                CompletableFuture<Document> result = new CompletableFuture<>();
                long start = System.nanoTime();
                office.submit(new Request(id, wanted, portfolio, result));
                Document document = result.get(); // the counter's complete() happens-before this returns
                long waitMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

                stats.recordWait(waitMs); // exactly once per document obtained
                portfolio.add(document);
                Log.info("Client %d got %s (#%d) from %s after %d ms",
                        id, wanted.name(), document.serialNo(), office.name(), waitMs);
            }
            stats.clientFinished();
            Log.info("Client %d finished: %s", id, plan.getLast().name());
        } catch (InterruptedException e) {
            // The simulation timed out and interrupted us: give up quietly.
            Thread.currentThread().interrupt();
            Log.info("Client %d gave up (interrupted)", id);
        } catch (ExecutionException e) {
            Log.info("Client %d was refused: %s", id, e.getCause().getMessage());
        } catch (RuntimeException e) {
            Log.info("Client %d failed: %s", id, e);
        } finally {
            done.countDown(); // always, or the simulation would wait until its timeout
        }
    }
}
