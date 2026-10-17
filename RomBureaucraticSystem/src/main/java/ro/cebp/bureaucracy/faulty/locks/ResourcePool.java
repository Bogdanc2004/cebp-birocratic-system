package ro.cebp.bureaucracy.faulty.locks;

import java.util.List;

/** Shared institutional resources used while a counter issues a document. */
public interface ResourcePool {
    void acquire(List<String> uses) throws InterruptedException;
    void release(List<String> uses);

    static ResourcePool none() {
        return NoOpResourcePool.INSTANCE;
    }
}
