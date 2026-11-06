package top.dext.bvc.backend.core;

/**
 * Whoever ran {@code /bvcs}.
 */
public interface BackendSender {

    /** {@code text} uses {@code &} colour codes. */
    void send(String text);
}
