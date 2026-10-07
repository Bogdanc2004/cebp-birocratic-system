package ro.cebp.bureaucracy.common;

import ro.cebp.bureaucracy.common.model.DocumentType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Decides, before any thread starts, which document each client wants and what it must obtain for it. */
public final class ClientPlans {

    private ClientPlans() {
    }

    /**
     * Returns client id (0..clients-1) -> plan, in id order. Runs on the main thread before
     * the clients start. Starting a thread (or submitting it to an executor) creates a
     * happens-before edge, so every client sees these lists fully built.
     * With a fixed {@code seed}, every run (and every version) gets the same plans.
     */
    public static Map<Integer, List<DocumentType>> assign(Bureaucracy bureaucracy) {
        Long seed = bureaucracy.simulation().seed();
        Random random = seed == null ? new Random() : new Random(seed);

        List<String> targets = new ArrayList<>(bureaucracy.documents().keySet());
        Collections.sort(targets); // stable order, so the seed gives the same result every time

        Map<Integer, List<DocumentType>> plans = new LinkedHashMap<>();
        for (int id = 0; id < bureaucracy.simulation().clients(); id++) {
            String target = targets.get(random.nextInt(targets.size()));
            plans.put(id, bureaucracy.graph().planFor(target));
        }
        return Collections.unmodifiableMap(plans);
    }
}
