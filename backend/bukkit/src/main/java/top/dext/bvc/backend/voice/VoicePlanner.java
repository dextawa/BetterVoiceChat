package top.dext.bvc.backend.voice;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import top.dext.bvc.api.server.VoiceMode;
import top.dext.bvc.api.server.ZoneSettings;
import top.dext.bvc.protocol.packet.BackendToProxy;
import top.dext.bvc.protocol.packet.BackendToProxy.PlayerFlags;

/**
 * Decides who can hear whom on this sub-server. Pure logic: no Bukkit types, no threads. Not
 * thread-safe; the engine calls it from a single planner thread.
 * <p>
 * Model, per player:
 * <ul>
 *   <li><b>zone</b>: an API room if the player is in one, else the world's zone if configured,
 *       else the sub-server default. Each zone is a <b>room</b> (isolation + broadcast target);
 *       the default zone is room 0.</li>
 *   <li><b>group</b>: who hears each other at any distance. The whole room in free mode, the team
 *       in team modes, none otherwise.</li>
 *   <li><b>neighbours</b>: in proximity modes, players of the same room and world within the radius
 *       (teammates excluded, they already hear through the group).</li>
 * </ul>
 */
public final class VoicePlanner {

    /** Lookup of API overrides by player. */
    public interface OverrideSource {
        PlayerOverrides get(UUID player);
    }

    static final String DEFAULT_ZONE_KEY = "default";
    private static final String SPECTATOR_SUFFIX = "#spectators";

    private final Interner rooms = new Interner(1);
    private final Interner groups = new Interner(1);

    public Map<Integer, PlayerPlan> plan(Collection<PlayerSnapshot> players, VoiceRules rules,
                                         Map<String, ZoneSettings> apiRooms, OverrideSource overrides) {
        Map<Integer, PlayerPlan> plans = new LinkedHashMap<Integer, PlayerPlan>(players.size() * 2);
        Map<Integer, PlayerSnapshot> snapshots = new HashMap<Integer, PlayerSnapshot>(players.size() * 2);

        for (PlayerSnapshot player : players) {
            PlayerOverrides override = overrides.get(player.uuid);
            if (override == null) {
                override = PlayerOverrides.NONE;
            }

            String zoneKey;
            String zoneName;
            ZoneSettings zone;
            ZoneSettings roomZone = override.room == null ? null : apiRooms.get(override.room);
            if (roomZone != null) {
                zoneKey = "room:" + override.room;
                zoneName = override.room;
                zone = roomZone;
            } else if (rules.worldZones.containsKey(player.world)) {
                zoneKey = "world:" + player.world;
                zoneName = player.world;
                zone = rules.worldZones.get(player.world);
            } else {
                zoneKey = DEFAULT_ZONE_KEY;
                zoneName = "";
                zone = rules.defaultZone;
            }

            if (player.spectator && rules.spectators == SpectatorPolicy.SEPARATE && zone.mode() != VoiceMode.DISABLED) {
                zoneKey = zoneKey + SPECTATOR_SUFFIX;
                zoneName = zoneName + SPECTATOR_SUFFIX;
                zone = new ZoneSettings(VoiceMode.FREE, zone.activation(), 0);
            }

            int roomId = DEFAULT_ZONE_KEY.equals(zoneKey) ? 0 : rooms.id(zoneKey);

            String groupKey = null;
            if (zone.mode() == VoiceMode.FREE) {
                groupKey = "free:" + zoneKey;
            } else if (zone.mode() == VoiceMode.TEAM || zone.mode() == VoiceMode.TEAM_PROXIMITY) {
                String team = override.group != null ? override.group : (rules.scoreboardTeams ? player.team : null);
                if (team != null) {
                    groupKey = "team:" + zoneKey + ":" + team;
                }
            }
            int groupId = groupKey == null ? 0 : groups.id(groupKey);

            boolean canSpeak = override.canSpeak != null ? override.canSpeak
                    : !(player.spectator && rules.spectators == SpectatorPolicy.LISTEN_ONLY);
            boolean canHear = override.canHear == null || override.canHear;
            boolean canBroadcast = override.canBroadcast != null ? override.canBroadcast : player.permissionBroadcast;
            int flags = (canSpeak ? PlayerFlags.CAN_SPEAK : 0) | (canHear ? PlayerFlags.CAN_HEAR : 0)
                    | (canBroadcast ? PlayerFlags.CAN_BROADCAST : 0);
            int priority = Math.max(0, Math.min(255, Math.max(override.priority, player.permissionPriority)));

            plans.put(player.id, new PlayerPlan(player.id, player.uuid, zoneName, zone, roomId, groupId, groupKey,
                    NO_NEIGHBORS, priority, flags));
            snapshots.put(player.id, player);
        }

        computeNeighbors(plans, snapshots);
        rooms.sweep();
        groups.sweep();
        return plans;
    }

    private static final int[] NO_NEIGHBORS = new int[0];

    /** Grid search per (room, world) bucket; cell size = radius, so only 27 cells are checked. */
    private static void computeNeighbors(Map<Integer, PlayerPlan> plans, Map<Integer, PlayerSnapshot> snapshots) {
        Map<String, List<PlayerPlan>> buckets = new HashMap<String, List<PlayerPlan>>();
        for (PlayerPlan plan : plans.values()) {
            VoiceMode mode = plan.zone.mode();
            if ((mode == VoiceMode.PROXIMITY || mode == VoiceMode.TEAM_PROXIMITY) && plan.zone.radius() > 0) {
                String key = plan.roomId + "|" + snapshots.get(plan.id).world;
                List<PlayerPlan> bucket = buckets.get(key);
                if (bucket == null) {
                    bucket = new ArrayList<PlayerPlan>();
                    buckets.put(key, bucket);
                }
                bucket.add(plan);
            }
        }

        for (List<PlayerPlan> bucket : buckets.values()) {
            if (bucket.size() < 2) {
                continue;
            }
            double radius = bucket.get(0).zone.radius();
            double radiusSquared = radius * radius;
            Map<Long, List<PlayerPlan>> cells = new HashMap<Long, List<PlayerPlan>>();
            for (PlayerPlan plan : bucket) {
                PlayerSnapshot s = snapshots.get(plan.id);
                long key = cellKey(cell(s.x, radius), cell(s.y, radius), cell(s.z, radius));
                List<PlayerPlan> cell = cells.get(key);
                if (cell == null) {
                    cell = new ArrayList<PlayerPlan>();
                    cells.put(key, cell);
                }
                cell.add(plan);
            }

            for (PlayerPlan plan : bucket) {
                PlayerSnapshot s = snapshots.get(plan.id);
                int cx = cell(s.x, radius);
                int cy = cell(s.y, radius);
                int cz = cell(s.z, radius);
                int[] found = new int[8];
                int count = 0;
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            List<PlayerPlan> cell = cells.get(cellKey(cx + dx, cy + dy, cz + dz));
                            if (cell == null) {
                                continue;
                            }
                            for (PlayerPlan other : cell) {
                                if (other.id == plan.id || (plan.groupId != 0 && other.groupId == plan.groupId)) {
                                    continue;
                                }
                                if (s.distanceSquared(snapshots.get(other.id)) <= radiusSquared) {
                                    if (count == found.length) {
                                        found = Arrays.copyOf(found, count * 2);
                                    }
                                    found[count++] = other.id;
                                }
                            }
                        }
                    }
                }
                if (count > 0) {
                    // The wire format caps lists; thousands of players in one spot is not a real case.
                    int[] neighbors = Arrays.copyOf(found, Math.min(count, BackendToProxy.MAX_ENTRIES));
                    Arrays.sort(neighbors);
                    plans.put(plan.id, plan.withNeighbors(neighbors));
                }
            }
        }
    }

    private static int cell(double coordinate, double size) {
        return (int) Math.floor(coordinate / size);
    }

    private static long cellKey(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }
}
