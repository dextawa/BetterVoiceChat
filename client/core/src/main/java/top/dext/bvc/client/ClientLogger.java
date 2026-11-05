package top.dext.bvc.client;

/**
 * Logging facade; adapters bridge it to the game's logger.
 */
public interface ClientLogger {

    void info(String message);

    void warn(String message);

    void warn(String message, Throwable error);

    void debug(String message);

    ClientLogger STDERR = new ClientLogger() {
        @Override
        public void info(String message) {
            System.err.println("[BVC] " + message);
        }

        @Override
        public void warn(String message) {
            System.err.println("[BVC] WARN " + message);
        }

        @Override
        public void warn(String message, Throwable error) {
            System.err.println("[BVC] WARN " + message + ": " + error);
        }

        @Override
        public void debug(String message) {
        }
    };
}
