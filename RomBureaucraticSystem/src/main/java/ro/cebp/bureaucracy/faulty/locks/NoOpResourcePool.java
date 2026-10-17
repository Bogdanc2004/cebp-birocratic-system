package ro.cebp.bureaucracy.faulty.locks;

import java.util.List;

final class NoOpResourcePool implements ResourcePool {
    static final NoOpResourcePool INSTANCE = new NoOpResourcePool();
    private NoOpResourcePool() { }
    @Override public void acquire(List<String> uses) { }
    @Override public void release(List<String> uses) { }
}
