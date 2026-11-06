package top.dext.bvc.backend.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import top.dext.bvc.api.server.Activation;
import top.dext.bvc.api.server.VoiceMode;
import top.dext.bvc.api.server.ZoneSettings;
import top.dext.bvc.protocol.packet.BackendToProxy;
import top.dext.bvc.protocol.packet.Packet;

class TableEmitterTest {

    private final VoicePlanner planner = new VoicePlanner();

    private static VoiceRules rules(VoiceMode mode) {
        return new VoiceRules(new ZoneSettings(mode, Activation.ANY, 10), Collections.emptyMap(), SpectatorPolicy.SAME, true);
    }

    private Map<Integer, PlayerPlan> plan(List<PlayerSnapshot> players, VoiceMode mode) {
        return planner.plan(players, rules(mode), Collections.emptyMap(), uuid -> null);
    }

    private static PlayerSnapshot at(int id, double x) {
        return new PlayerSnapshot(id, new UUID(0, id), "world", x, 64, 0, false, null, 0, false);
    }

    @Test
    void firstPassSendsEverythingThenOnlyChanges() {
        TableEmitter emitter = new TableEmitter(1000);
        List<PlayerSnapshot> players = new ArrayList<>(List.of(at(1, 0), at(2, 5), at(3, 100)));
        Map<Integer, PlayerPlan> first = plan(players, VoiceMode.PROXIMITY);

        List<Packet<BackendToProxy.Handler>> initial = emitter.diff(Collections.emptyMap(), first, false);
        assertEquals(List.of(BackendToProxy.Membership.class, BackendToProxy.PlayerFlags.class,
                BackendToProxy.ZoneInfo.class, BackendToProxy.Neighbors.class), types(initial));
        assertEquals(2, ((BackendToProxy.Neighbors) initial.get(3)).entries.size(), "player 3 has nobody in range");

        assertTrue(emitter.diff(first, plan(players, VoiceMode.PROXIMITY), false).isEmpty(), "nothing changed, nothing sent");

        players.set(2, at(3, 8)); // player 3 walks over
        Map<Integer, PlayerPlan> third = plan(players, VoiceMode.PROXIMITY);
        List<Packet<BackendToProxy.Handler>> moved = emitter.diff(first, third, false);
        assertEquals(List.of(BackendToProxy.Neighbors.class), types(moved));
        assertEquals(3, ((BackendToProxy.Neighbors) moved.get(0)).entries.size());

        List<Packet<BackendToProxy.Handler>> full = emitter.diff(third, third, true);
        assertEquals(4, full.size());
        assertEquals(3, ((BackendToProxy.Neighbors) full.get(3)).entries.size(), "full resend includes every player");
    }

    @Test
    void sequencesIncreasePerPacket() {
        TableEmitter emitter = new TableEmitter(5000);
        List<Packet<BackendToProxy.Handler>> packets = emitter.diff(Collections.emptyMap(),
                plan(List.of(at(1, 0), at(2, 1)), VoiceMode.PROXIMITY), false);
        long last = 4999;
        for (Packet<BackendToProxy.Handler> packet : packets) {
            long sequence = sequenceOf(packet);
            assertTrue(sequence > last);
            last = sequence;
        }
    }

    @Test
    void largeTablesAreSplitUnderTheBukkitLimit() {
        List<PlayerSnapshot> crowd = new ArrayList<>();
        for (int i = 1; i <= 400; i++) {
            crowd.add(at(i, (i % 20) * 0.4));
        }
        List<Packet<BackendToProxy.Handler>> packets = new TableEmitter(1).diff(Collections.emptyMap(),
                plan(crowd, VoiceMode.PROXIMITY), false);
        Set<Integer> speakers = new HashSet<>();
        int neighborPackets = 0;
        for (Packet<BackendToProxy.Handler> packet : packets) {
            int size = BackendToProxy.REGISTRY.encode(packet).length;
            assertTrue(size <= 32_766, "payload " + size + " exceeds the 1.8 limit");
            if (packet instanceof BackendToProxy.Neighbors neighbors) {
                neighborPackets++;
                for (BackendToProxy.Neighbors.Entry entry : neighbors.entries) {
                    speakers.add(entry.speakerId);
                    assertEquals(399, entry.listenerIds.length);
                }
            }
        }
        assertTrue(neighborPackets > 1, "400 x 399 ids do not fit one packet");
        assertEquals(400, speakers.size(), "no entry lost while splitting");
    }

    @Test
    void zoneInfoCarriesModeActivationAndRadius() {
        Map<String, ZoneSettings> worlds = new HashMap<>();
        worlds.put("world", new ZoneSettings(VoiceMode.TEAM_PROXIMITY, Activation.PTT_ONLY, 24));
        VoiceRules rules = new VoiceRules(ZoneSettings.of(VoiceMode.FREE), worlds, SpectatorPolicy.SAME, true);
        Map<Integer, PlayerPlan> plans = planner.plan(List.of(at(1, 0)), rules, Collections.emptyMap(), uuid -> null);
        BackendToProxy.ZoneInfo zone = (BackendToProxy.ZoneInfo) new TableEmitter(1).diff(Collections.emptyMap(), plans, false)
                .stream().filter(p -> p instanceof BackendToProxy.ZoneInfo).findFirst().orElseThrow();
        BackendToProxy.ZoneInfo.Entry entry = zone.entries.get(0);
        assertEquals("world", entry.room);
        assertEquals(top.dext.bvc.protocol.data.VoiceMode.TEAM_PROXIMITY, entry.mode);
        assertEquals(top.dext.bvc.protocol.data.Activation.PTT_ONLY, entry.activation);
        assertEquals(24f, entry.radius);
    }

    private static List<Class<?>> types(List<Packet<BackendToProxy.Handler>> packets) {
        List<Class<?>> types = new ArrayList<>();
        for (Packet<BackendToProxy.Handler> packet : packets) {
            types.add(packet.getClass());
        }
        return types;
    }

    private static long sequenceOf(Packet<BackendToProxy.Handler> packet) {
        if (packet instanceof BackendToProxy.Membership p) {
            return p.sequence;
        }
        if (packet instanceof BackendToProxy.PlayerFlags p) {
            return p.sequence;
        }
        if (packet instanceof BackendToProxy.ZoneInfo p) {
            return p.sequence;
        }
        return ((BackendToProxy.Neighbors) packet).sequence;
    }
}
