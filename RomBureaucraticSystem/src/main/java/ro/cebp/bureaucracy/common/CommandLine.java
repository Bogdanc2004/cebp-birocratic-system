package ro.cebp.bureaucracy.common;

/** Tiny parser for the options shared by all entry points: {@code --config <path>}, {@code --runs <n>}, {@code --quiet}. */
public record CommandLine(String config, int runs, boolean quiet, String mode) {

    public static final String DEFAULT_CONFIG = "config/default.json";

    /** Any argument that is not an option is taken as the mode (used by LocksMain). */
    public static CommandLine parse(String[] args) {
        String config = DEFAULT_CONFIG;
        int runs = 1;
        boolean quiet = false;
        String mode = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> config = value(args, ++i, "--config");
                case "--runs" -> runs = Integer.parseInt(value(args, ++i, "--runs"));
                case "--quiet" -> quiet = true;
                default -> {
                    if (args[i].startsWith("--")) {
                        throw new IllegalArgumentException("Unknown option: " + args[i]);
                    }
                    mode = args[i];
                }
            }
        }
        if (runs < 1) {
            throw new IllegalArgumentException("--runs must be at least 1");
        }
        return new CommandLine(config, runs, quiet, mode);
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException(option + " needs a value");
        }
        return args[index];
    }
}
