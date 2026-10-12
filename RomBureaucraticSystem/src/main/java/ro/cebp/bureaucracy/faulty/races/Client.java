package ro.cebp.bureaucracy.faulty.races;

import ro.cebp.bureaucracy.common.Statistics;
import ro.cebp.bureaucracy.common.model.DocumentType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

// Stub for M4 in faulty races package.
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
