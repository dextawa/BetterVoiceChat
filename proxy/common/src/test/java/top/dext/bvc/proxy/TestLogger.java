package top.dext.bvc.proxy;

import java.util.function.Consumer;

import top.dext.bvc.proxy.platform.BvcLogger;

/**
 * Logger for tests: warnings and errors go to stderr, the rest is dropped.
 */
public final class TestLogger implements BvcLogger {

    private final Consumer<String> sink;

    public TestLogger() {
        this(System.err::println);
    }

    public TestLogger(Consumer<String> sink) {
        this.sink = sink;
    }

    @Override
    public void info(String message) {
    }

    @Override
    public void warn(String message) {
        sink.accept("WARN " + message);
    }

    @Override
    public void warn(String message, Throwable error) {
        sink.accept("WARN " + message + ": " + error);
    }

    @Override
    public void error(String message, Throwable error) {
        sink.accept("ERROR " + message + ": " + error);
    }

    @Override
    public void debug(String message) {
    }
}
