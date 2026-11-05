package top.dext.bvc.proxy.routing;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import top.dext.bvc.protocol.data.Activation;
import top.dext.bvc.protocol.data.VoiceMode;

/**
 * Routing tables of one sub-server, filled by its BVC plugin.
 * <p>
 * Writes are synchronized (they come from backend messages, a few per second); reads are
 * lock-free because the router reads on every audio frame.
 */
public final class ServerRoutes {

    private final String server;
    private final ConcurrentHashMap<Integer, PlayerRoute> routes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Set<Integer>> groups = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Set<Integer>> rooms = new ConcurrentHashMap<>();

    private volatile boolean backendPresent;
    private volatile String backendVersion = "";
    private volatile String backendSoftware = "";
    private volatile long backendInstance;

    ServerRoutes(String server) {
        this.server = server;
    }

    public String server() {
        return server;
    }

    public boolean backendPresent() {
        return backendPresent;
    }

    public String backendVersion() {
        return backendVersion;
    }

    public String backendSoftware() {
        return backendSoftware;
    }

    /**
     * The backend announced itself. A new backend instance (it restarted) means everything it
     * told us before is void; the same instance greeting a new proxy keeps its state.
     *
     * @return true if the tables were reset
     */
    public synchronized boolean onBackendHello(String version, String software, long instanceId) {
        boolean reset = !backendPresent || backendInstance != instanceId;
        if (reset) {
            routes.clear();
            groups.clear();
            rooms.clear();
        }
        backendVersion = version;
        backendSoftware = software;
        backendInstance = instanceId;
        backendPresent = true;
        return reset;
    }

    public PlayerRoute route(int playerId) {
        return routes.get(playerId);
    }

    public Set<Integer> groupMembers(int groupId) {
        Set<Integer> members = groups.get(groupId);
        return members == null ? Collections.<Integer>emptySet() : members;
    }

    public Set<Integer> roomMembers(int roomId) {
        Set<Integer> members = rooms.get(roomId);
        return members == null ? Collections.<Integer>emptySet() : members;
    }

    public synchronized void applyMembership(long sequence, int playerId, int roomId, int groupId) {
        PlayerRoute current = routes.getOrDefault(playerId, PlayerRoute.INITIAL);
        if (sequence < current.membershipSequence()) {
            return;
        }
        boolean known = routes.containsKey(playerId);
        if (known && current.roomId() != roomId) {
            removeFrom(rooms, current.roomId(), playerId);
        }
        if (known && current.groupId() != groupId && current.groupId() != 0) {
            removeFrom(groups, current.groupId(), playerId);
        }
        routes.put(playerId, current.withMembership(sequence, roomId, groupId));
        rooms.computeIfAbsent(roomId, key -> ConcurrentHashMap.newKeySet()).add(playerId);
        if (groupId != 0) {
            groups.computeIfAbsent(groupId, key -> ConcurrentHashMap.newKeySet()).add(playerId);
        }
    }

    public synchronized void applyNeighbors(long sequence, int playerId, int[] listeners) {
        PlayerRoute current = currentOrIndexed(playerId);
        if (sequence >= current.neighborsSequence()) {
            routes.put(playerId, current.withNeighbors(sequence, listeners));
        }
    }

    public synchronized void applyFlags(long sequence, int playerId, int priority, int flags) {
        PlayerRoute current = currentOrIndexed(playerId);
        if (sequence >= current.flagsSequence()) {
            routes.put(playerId, current.withFlags(sequence, Math.max(0, Math.min(255, priority)), flags));
        }
    }

    /** @return true if the zone visible to the client changed */
    public synchronized boolean applyZone(long sequence, int playerId, String room, VoiceMode mode, Activation activation, float radius) {
        PlayerRoute current = currentOrIndexed(playerId);
        if (sequence < current.zoneSequence()) {
            return false;
        }
        boolean changed = !room.equals(current.room()) || mode != current.mode() || activation != current.activation()
                || Float.compare(radius, current.radius()) != 0;
        routes.put(playerId, current.withZone(sequence, room, mode, activation, radius));
        return changed;
    }

    /** Player left this sub-server. */
    public synchronized void remove(int playerId) {
        PlayerRoute route = routes.remove(playerId);
        if (route != null) {
            removeFrom(rooms, route.roomId(), playerId);
            if (route.groupId() != 0) {
                removeFrom(groups, route.groupId(), playerId);
            }
        }
    }

    public int size() {
        return routes.size();
    }

    /** A route seen for the first time joins the default room so broadcasts reach it. */
    private PlayerRoute currentOrIndexed(int playerId) {
        PlayerRoute current = routes.get(playerId);
        if (current == null) {
            current = PlayerRoute.INITIAL;
            routes.put(playerId, current);
            rooms.computeIfAbsent(current.roomId(), key -> ConcurrentHashMap.newKeySet()).add(playerId);
        }
        return current;
    }

    private static void removeFrom(ConcurrentHashMap<Integer, Set<Integer>> index, int key, int playerId) {
        index.computeIfPresent(key, (k, members) -> {
            members.remove(playerId);
            return members.isEmpty() ? null : members;
        });
    }
}
