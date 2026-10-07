package ro.cebp.bureaucracy.common;

/** Thrown when the configuration file is missing, malformed or logically invalid. */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
