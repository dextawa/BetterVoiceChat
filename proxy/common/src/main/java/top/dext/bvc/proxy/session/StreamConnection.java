package top.dext.bvc.proxy.session;

/**
 * An authenticated stream ("A") connection, implemented by the Netty handler.
 */
public interface StreamConnection {

    /** Queues an encoded packet (without length prefix). Thread-safe, never blocks. */
    void send(byte[] packet);

    void close();

    boolean isOpen();
}
