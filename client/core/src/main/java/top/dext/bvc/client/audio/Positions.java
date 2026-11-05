package top.dext.bvc.client.audio;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Latest positions from the game, written by the adapter once per client tick and read by the
 * playback thread. Coordinates are Minecraft's: x east, z south, yaw 0 = facing south.
 */
public final class Positions {

    private volatile double[] listener;
    private volatile Map<UUID, double[]> players = Collections.emptyMap();

    /**
     * @param others player UUID -&gt; {x, y, z} for every other player the client can see
     */
    public void update(double x, double y, double z, float yaw, Map<UUID, double[]> others) {
        listener = new double[] {x, y, z, yaw};
        players = others;
    }

    public void clear() {
        listener = null;
        players = Collections.emptyMap();
    }

    /** {x, y, z, yaw} or {@code null} when not in a world. */
    public double[] listener() {
        return listener;
    }

    /** {x, y, z} or {@code null} if that player is not loaded on this client. */
    public double[] position(UUID player) {
        return player == null ? null : players.get(player);
    }
}
