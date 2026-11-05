package top.dext.bvc.proxy.platform;

/**
 * Logging facade: Velocity hands plugins an SLF4J logger, BungeeCord a JUL one.
 */
public interface BvcLogger {

    void info(String message);

    void warn(String message);

    void warn(String message, Throwable error);

    void error(String message, Throwable error);

    void debug(String message);
}
