package top.dext.bvc.backend.core;

/**
 * The platform's logger.
 */
public interface BackendLog {

    void info(String message);

    void warn(String message);

    void error(String message, Throwable error);
}
