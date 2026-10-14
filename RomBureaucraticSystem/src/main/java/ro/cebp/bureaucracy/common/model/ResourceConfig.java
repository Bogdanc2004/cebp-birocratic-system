package ro.cebp.bureaucracy.common.model;

/**
 * A resource shared by every office of the institution, e.g. the official stamp.
 *
 * @param permits how many identical copies exist (1 = a single stamp for everybody)
 */
public record ResourceConfig(String name, int permits) {
}
