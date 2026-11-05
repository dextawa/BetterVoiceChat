package top.dext.bvc.proxy.transport;

import top.dext.bvc.protocol.packet.ClientToProxy;
import top.dext.bvc.protocol.packet.Packet;
import top.dext.bvc.proxy.config.BvcConfig;
import top.dext.bvc.proxy.platform.BvcLogger;
import top.dext.bvc.proxy.session.StreamConnection;
import top.dext.bvc.proxy.session.VoiceSession;

/**
 * What the Netty stream handlers need from the proxy core.
 */
public interface StreamGateway {

    BvcConfig config();

    BvcLogger logger();

    /** The session a stream claims to belong to, or {@code null}. */
    VoiceSession sessionForAuth(int sessionId);

    void onStreamAuthenticated(VoiceSession session, StreamConnection connection);

    void onStreamPacket(VoiceSession session, Packet<ClientToProxy.Handler> packet);

    void onStreamClosed(VoiceSession session, StreamConnection connection);
}
