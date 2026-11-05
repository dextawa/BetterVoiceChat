package top.dext.bvc.proxy.session;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import top.dext.bvc.proxy.platform.PlatformPlayer;

/**
 * All voice sessions, indexed by UUID, compact id and current sub-server.
 * <p>
 * Ids are never reused while the proxy runs, so a stale id on a client can never point at the
 * wrong player.
 */
public final class SessionManager {

    private final ConcurrentHashMap<UUID, VoiceSession> byUuid = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, VoiceSession> byId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<VoiceSession>> byServer = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    public VoiceSession create(PlatformPlayer player, long now) {
        VoiceSession session = new VoiceSession(nextId.getAndIncrement(), player, now);
        VoiceSession previous = byUuid.put(player.uuid(), session);
        if (previous != null) {
            detach(previous);
        }
        byId.put(session.id(), session);
        return session;
    }

    /** Removes the session if it is still the current one for that player. */
    public VoiceSession remove(UUID uuid) {
        VoiceSession session = byUuid.remove(uuid);
        if (session != null) {
            detach(session);
        }
        return session;
    }

    private void detach(VoiceSession session) {
        byId.remove(session.id(), session);
        moveToServer(session, null);
        session.resetVoice();
    }

    public VoiceSession get(UUID uuid) {
        return byUuid.get(uuid);
    }

    public VoiceSession get(int id) {
        return byId.get(id);
    }

    public Collection<VoiceSession> all() {
        return Collections.unmodifiableCollection(byUuid.values());
    }

    public Set<VoiceSession> onServer(String server) {
        Set<VoiceSession> sessions = server == null ? null : byServer.get(server);
        return sessions == null ? Collections.<VoiceSession>emptySet() : Collections.unmodifiableSet(sessions);
    }

    /** Updates the server index; {@code null} = not on any server. Returns the previous server. */
    public String moveToServer(VoiceSession session, String server) {
        String previous = session.server();
        if (previous != null) {
            Set<VoiceSession> old = byServer.get(previous);
            if (old != null) {
                old.remove(session);
            }
        }
        session.setServer(server);
        if (server != null && byId.get(session.id()) == session) {
            byServer.computeIfAbsent(server, key -> ConcurrentHashMap.newKeySet()).add(session);
        }
        return previous;
    }

    public int size() {
        return byUuid.size();
    }
}
