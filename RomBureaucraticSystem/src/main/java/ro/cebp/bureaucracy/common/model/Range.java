package ro.cebp.bureaucracy.common.model;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Inclusive integer interval, e.g. a service time of 20..80 ms.
 * JSON form: {@code {"min": 20, "max": 80}}.
 */
public record Range(int min, int max) {

    /**
     * Random value in [min, max]. Uses {@link ThreadLocalRandom}, so threads never
     * contend on a shared {@code java.util.Random}.
     */
    public int random() {
        return min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
    }
}
