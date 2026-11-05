package top.dext.bvc.proxy.routing;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Routing tables per sub-server.
 */
public final class RouteRegistry {

    private final ConcurrentHashMap<String, ServerRoutes> servers = new ConcurrentHashMap<>();

    public ServerRoutes get(String server) {
        return server == null ? null : servers.get(server);
    }

    public ServerRoutes getOrCreate(String server) {
        return servers.computeIfAbsent(server, ServerRoutes::new);
    }

    /** Removes a player from every table except {@code keep} (normally only one has them). */
    public void removeEverywhere(int playerId, String keep) {
        for (ServerRoutes routes : servers.values()) {
            if (!routes.server().equals(keep)) {
                routes.remove(playerId);
            }
        }
    }

    public Iterable<ServerRoutes> all() {
        return servers.values();
    }
}
