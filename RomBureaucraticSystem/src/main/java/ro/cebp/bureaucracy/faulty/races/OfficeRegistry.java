package ro.cebp.bureaucracy.faulty.races;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FAULTY on purpose. Global "directory" of offices, created lazily.
 *
 * <p>BUG [R6] — unsafe lazy initialization (check-then-act). Several offices open at the
 * same time, each on its own thread, and each one registers itself. If two threads see
 * {@code instance == null} together, each creates its <b>own</b> registry. Offices end up
 * spread over different registries, and the one left in {@code instance} is missing some.
 * Clients then cannot find those offices. {@code instance} is also not {@code volatile},
 * so a thread may even see a partially built registry.
 *
 * <p>The map inside is a {@code ConcurrentHashMap}. A thread-safe collection does not help
 * when the object holding it is not created safely.
 *
 * <p>Fix (any one): eager {@code static final} instance; the holder-class idiom;
 * {@code synchronized getInstance()}; or double-checked locking with a {@code volatile} field.
 */
public final class OfficeRegistry {

    private static OfficeRegistry instance; // BUG [R6]: not volatile, not guarded

    private final Map<String, Office> offices = new ConcurrentHashMap<>();

    private OfficeRegistry() {
        loadArchive();
    }

    public static OfficeRegistry getInstance() {
        if (instance == null) {                 // check
            instance = new OfficeRegistry();    // act: another thread may do the same in between
        }
        return instance;
    }

    public void register(Office office) {
        offices.put(office.name(), office);
    }

    /** Returns null if the office is not in this registry. */
    public Office lookup(String name) {
        return offices.get(name);
    }

    /** Forgets the current instance, so each run of RacesMain starts from scratch. */
    static void reset() {
        instance = null;
    }

    /** Stands for slow initialization. It widens the window between the check and the act. */
    private static void loadArchive() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
