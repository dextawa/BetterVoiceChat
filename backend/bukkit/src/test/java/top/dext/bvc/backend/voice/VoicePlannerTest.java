package top.dext.bvc.backend.voice;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import top.dext.bvc.api.server.Activation;
import top.dext.bvc.api.server.VoiceMode;
import top.dext.bvc.api.server.ZoneSettings;
import top.dext.bvc.protocol.packet.BackendToProxy.PlayerFlags;

class VoicePlannerTest {

    private final VoicePlanner planner = new VoicePlanner();
    private final Map<String, ZoneSettings> rooms = new HashMap<>();
    private final Map<UUID, PlayerOverrides> overrides = new HashMap<>();
    private final List<PlayerSnapshot> players = new ArrayList<>();

    private static VoiceRules rules(VoiceMode mode) {
        return new VoiceRules(new ZoneSettings(mode, Activation.ANY, 10), Collections.emptyMap(), SpectatorPolicy.SAME, true);
    }

    private PlayerSnapshot add(int id, String world, double x, double y, double z) {
        return add(id, world, x, y, z, null, false);
    }

    private PlayerSnapshot add(int id, String world, double x, double y, double z, String team, boolean spectator) {
        PlayerSnapshot snapshot = new PlayerSnapshot(id, new UUID(0, id + 1000), world, x, y, z, spectator, team, 0, false);
        players.add(snapshot);
        return snapshot;
    }

    private Map<Integer, PlayerPlan> plan(VoiceRules rules) {
        return planner.plan(players, rules, rooms, uuid -> overrides.get(uuid));
    }

    @Test
    void freeModePutsEveryoneInOneGroupWithoutNeighbours() {
        add(1, "world", 0, 64, 0);
        add(2, "world_nether", 5000, 64, 0);
        Map<Integer, PlayerPlan> plans = plan(rules(VoiceMode.FREE));

        assertEquals(0, plans.get(1).roomId);
        assertNotEquals(0, plans.get(1).groupId);
        assertEquals(plans.get(1).groupId, plans.get(2).groupId, "free mode spans the whole sub-server, every world");
        assertEquals(0, plans.get(1).neighbors.length);
        assertEquals("", plans.get(1).zoneName);
    }

    @Test
    void proximityUsesThreeDimensionalRadiusAndSameWorld() {
        add(1, "world", 0, 64, 0);
        add(2, "world", 6, 64, 6);          // ~8.5 blocks: in range of 10
        add(3, "world", 0, 80, 0);          // 16 above: out of range
        add(4, "world_nether", 1, 64, 1);   // close, but another world
        add(5, "world", 25, 64, 0);         // far
        add(6, "world", 9.9, 64, 0);        // just inside, across a cell boundary
        Map<Integer, PlayerPlan> plans = plan(rules(VoiceMode.PROXIMITY));

        assertArrayEquals(new int[] {2, 6}, plans.get(1).neighbors);
        assertArrayEquals(new int[] {1, 6}, plans.get(2).neighbors);
        assertArrayEquals(new int[] {}, plans.get(3).neighbors);
        assertArrayEquals(new int[] {}, plans.get(4).neighbors);
        assertEquals(0, plans.get(1).groupId, "no groups in pure proximity");
    }

    @Test
    void proximityWorksAroundNegativeCoordinates() {
        add(1, "world", -0.5, 64, -0.5);
        add(2, "world", 0.5, 64, 0.5);
        add(3, "world", -12, 64, -1);
        Map<Integer, PlayerPlan> plans = plan(rules(VoiceMode.PROXIMITY));
        assertArrayEquals(new int[] {2}, plans.get(1).neighbors);
        assertArrayEquals(new int[] {}, plans.get(3).neighbors);
    }

    @Test
    void teamModeGroupsByScoreboardTeamAndApiGroupWins() {
        PlayerSnapshot red1 = add(1, "world", 0, 64, 0, "red", false);
        add(2, "world", 999, 64, 0, "red", false);
        add(3, "world", 1, 64, 0, "blue", false);
        add(4, "world", 1, 64, 0, null, false);
        PlayerSnapshot overridden = add(5, "world", 1, 64, 0, "blue", false);
        overrides.put(overridden.uuid, PlayerOverrides.NONE.withGroup("red"));

        Map<Integer, PlayerPlan> plans = plan(rules(VoiceMode.TEAM));

        assertEquals(plans.get(1).groupId, plans.get(2).groupId, "teammates hear each other at any distance");
        assertNotEquals(plans.get(1).groupId, plans.get(3).groupId);
        assertEquals(0, plans.get(4).groupId, "no team: hears nobody in team mode");
        assertEquals(plans.get(1).groupId, plans.get(5).groupId);
        assertEquals(0, plans.get(1).neighbors.length, "team mode has no proximity");
        assertEquals("team:default:red", plans.get(red1.id).groupKey);
    }

    @Test
    void scoreboardTeamsCanBeIgnored() {
        add(1, "world", 0, 64, 0, "red", false);
        add(2, "world", 0, 64, 0, "red", false);
        VoiceRules apiOnly = new VoiceRules(new ZoneSettings(VoiceMode.TEAM, Activation.ANY, 10), Collections.emptyMap(),
                SpectatorPolicy.SAME, false);
        assertEquals(0, plan(apiOnly).get(1).groupId);
    }

    @Test
    void teamProximityExcludesTeammatesFromNeighbours() {
        add(1, "world", 0, 64, 0, "red", false);
        add(2, "world", 1, 64, 0, "red", false);
        add(3, "world", 2, 64, 0, "blue", false);
        Map<Integer, PlayerPlan> plans = plan(rules(VoiceMode.TEAM_PROXIMITY));
        assertArrayEquals(new int[] {3}, plans.get(1).neighbors);
        assertArrayEquals(new int[] {1, 2}, plans.get(3).neighbors);
    }

    @Test
    void worldZonesAreIsolatedRooms() {
        Map<String, ZoneSettings> worlds = new HashMap<>();
        worlds.put("arena", new ZoneSettings(VoiceMode.FREE, Activation.PTT_ONLY, 0));
        VoiceRules rules = new VoiceRules(new ZoneSettings(VoiceMode.PROXIMITY, Activation.ANY, 10), worlds,
                SpectatorPolicy.SAME, true);
        add(1, "world", 0, 64, 0);
        add(2, "arena", 0, 64, 0);
        add(3, "arena", 500, 64, 0);
        Map<Integer, PlayerPlan> plans = plan(rules);

        assertEquals(0, plans.get(1).roomId);
        assertNotEquals(0, plans.get(2).roomId);
        assertEquals(plans.get(2).roomId, plans.get(3).roomId);
        assertEquals(plans.get(2).groupId, plans.get(3).groupId);
        assertEquals("arena", plans.get(2).zoneName);
        assertEquals(Activation.PTT_ONLY, plans.get(2).zone.activation());
    }

    @Test
    void apiRoomsOverrideWorldsAndIsolatePlayers() {
        rooms.put("arena-1", ZoneSettings.of(VoiceMode.FREE));
        rooms.put("arena-2", ZoneSettings.of(VoiceMode.FREE));
        PlayerSnapshot a = add(1, "world", 0, 64, 0);
        PlayerSnapshot b = add(2, "world", 0, 64, 0);
        PlayerSnapshot c = add(3, "world", 0, 64, 0);
        add(4, "world", 0, 64, 0);
        overrides.put(a.uuid, PlayerOverrides.NONE.withRoom("arena-1"));
        overrides.put(b.uuid, PlayerOverrides.NONE.withRoom("arena-1"));
        overrides.put(c.uuid, PlayerOverrides.NONE.withRoom("arena-2"));
        Map<Integer, PlayerPlan> plans = plan(rules(VoiceMode.PROXIMITY));

        assertEquals(plans.get(1).groupId, plans.get(2).groupId);
        assertNotEquals(plans.get(1).groupId, plans.get(3).groupId);
        assertNotEquals(plans.get(1).roomId, plans.get(3).roomId);
        assertEquals(0, plans.get(4).roomId);
        assertArrayEquals(new int[] {}, plans.get(4).neighbors, "room members are not proximity neighbours of default-zone players");
    }

    @Test
    void unknownRoomFallsBackToDefault() {
        PlayerSnapshot a = add(1, "world", 0, 64, 0);
        overrides.put(a.uuid, PlayerOverrides.NONE.withRoom("deleted"));
        assertEquals(0, plan(rules(VoiceMode.FREE)).get(1).roomId);
    }

    @Test
    void spectatorPolicies() {
        add(1, "world", 0, 64, 0, null, false);
        add(2, "world", 1, 64, 0, null, true);
        add(3, "world", 2, 64, 0, null, true);

        Map<Integer, PlayerPlan> same = plan(new VoiceRules(new ZoneSettings(VoiceMode.PROXIMITY, Activation.ANY, 10),
                Collections.emptyMap(), SpectatorPolicy.SAME, true));
        assertArrayEquals(new int[] {2, 3}, same.get(1).neighbors);

        Map<Integer, PlayerPlan> listenOnly = plan(new VoiceRules(new ZoneSettings(VoiceMode.PROXIMITY, Activation.ANY, 10),
                Collections.emptyMap(), SpectatorPolicy.LISTEN_ONLY, true));
        assertEquals(0, listenOnly.get(2).flags & PlayerFlags.CAN_SPEAK);
        assertTrue((listenOnly.get(2).flags & PlayerFlags.CAN_HEAR) != 0);
        assertTrue((listenOnly.get(1).flags & PlayerFlags.CAN_SPEAK) != 0);

        Map<Integer, PlayerPlan> separate = plan(new VoiceRules(new ZoneSettings(VoiceMode.PROXIMITY, Activation.ANY, 10),
                Collections.emptyMap(), SpectatorPolicy.SEPARATE, true));
        assertArrayEquals(new int[] {}, separate.get(1).neighbors, "the living do not hear spectators");
        assertEquals(separate.get(2).groupId, separate.get(3).groupId, "spectators hear each other");
        assertNotEquals(separate.get(1).roomId, separate.get(2).roomId);
        assertEquals(VoiceMode.FREE, separate.get(2).zone.mode());
        assertEquals("#spectators", separate.get(2).zoneName);
    }

    @Test
    void priorityAndBroadcastCombinePermissionsAndOverrides() {
        PlayerSnapshot commander = new PlayerSnapshot(1, new UUID(0, 1), "world", 0, 0, 0, false, null, 50, true);
        players.add(commander);
        PlayerSnapshot captain = add(2, "world", 0, 0, 0);
        overrides.put(captain.uuid, PlayerOverrides.NONE.withPriority(300).withCanBroadcast(true));
        overrides.put(commander.uuid, PlayerOverrides.NONE.withPriority(10).withCanBroadcast(false).withCanHear(false));

        Map<Integer, PlayerPlan> plans = plan(rules(VoiceMode.FREE));
        assertEquals(50, plans.get(1).priority, "the higher of permission and override");
        assertEquals(0, plans.get(1).flags & PlayerFlags.CAN_BROADCAST, "override wins over permission");
        assertEquals(0, plans.get(1).flags & PlayerFlags.CAN_HEAR);
        assertEquals(255, plans.get(2).priority, "clamped");
        assertTrue((plans.get(2).flags & PlayerFlags.CAN_BROADCAST) != 0);
    }

    @Test
    void disabledZoneHasNoGroupsOrNeighbours() {
        add(1, "world", 0, 64, 0);
        add(2, "world", 0, 64, 0);
        Map<Integer, PlayerPlan> plans = plan(rules(VoiceMode.DISABLED));
        assertEquals(0, plans.get(1).groupId);
        assertEquals(0, plans.get(1).neighbors.length);
        assertNull(plans.get(1).groupKey);
    }

    @Test
    void groupIdsStayStableWhileInUse() {
        add(1, "world", 0, 64, 0, "red", false);
        add(2, "world", 0, 64, 0, "blue", false);
        int red = plan(rules(VoiceMode.TEAM)).get(1).groupId;
        int blue = plan(rules(VoiceMode.TEAM)).get(2).groupId;
        assertEquals(red, plan(rules(VoiceMode.TEAM)).get(1).groupId);
        assertEquals(blue, plan(rules(VoiceMode.TEAM)).get(2).groupId);
    }

    @Test
    void crowdedSpotStaysCorrect() {
        for (int i = 1; i <= 300; i++) {
            // 4.5 x 6 blocks: everyone within the 10-block radius of everyone, spread over cells.
            add(i, "world", (i % 10) * 0.5, 64, (i / 10) * 0.2);
        }
        Map<Integer, PlayerPlan> plans = plan(rules(VoiceMode.PROXIMITY));
        for (PlayerPlan plan : plans.values()) {
            assertEquals(299, plan.neighbors.length);
        }
    }
}
