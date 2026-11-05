package top.dext.bvc.proxy.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static top.dext.bvc.proxy.routing.RouterHarness.player;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import top.dext.bvc.api.MuteScope;
import top.dext.bvc.protocol.data.AudioCategory;
import top.dext.bvc.protocol.data.MuteKind;
import top.dext.bvc.protocol.data.VoiceMode;
import top.dext.bvc.protocol.packet.BackendToProxy.PlayerFlags;
import top.dext.bvc.protocol.packet.ClientToProxy;
import top.dext.bvc.protocol.packet.ProxyToClient;
import top.dext.bvc.protocol.packet.ProxyToClient.VoiceAudio;
import top.dext.bvc.proxy.session.VoiceSession;

class VoiceRouterTest {

    private RouterHarness h;

    @AfterEach
    void close() {
        if (h != null) {
            h.close();
        }
    }

    private static List<VoiceAudio> audio(VoiceSession session) {
        return player(session).received(VoiceAudio.class);
    }

    @Test
    void freeModeReachesEveryoneInTheGroup() throws Exception {
        h = new RouterHarness();
        VoiceSession a = h.join("Alice", "lobby");
        VoiceSession b = h.join("Bob", "lobby");
        VoiceSession c = h.join("Carol", "lobby");
        h.setupServer("lobby", VoiceMode.FREE, a, b, c);
        h.membership("lobby", 0, 1, a, b, c);
        h.clearAll();

        h.speak(a);

        assertEquals(0, audio(a).size(), "speaker never hears themself");
        assertEquals(1, audio(b).size());
        assertEquals(1, audio(c).size());
        VoiceAudio frame = audio(b).get(0);
        assertEquals(a.id(), frame.speakerId);
        assertEquals(AudioCategory.GROUP, frame.category);
    }

    @Test
    void voiceNeverCrossesSubServers() throws Exception {
        h = new RouterHarness();
        VoiceSession a = h.join("Alice", "lobby");
        VoiceSession b = h.join("Bob", "lobby");
        VoiceSession elsewhere = h.join("Eve", "bedwars");
        h.setupServer("lobby", VoiceMode.FREE, a, b);
        h.membership("lobby", 0, 1, a, b);
        // A buggy or malicious backend table naming a player who is on another sub-server:
        h.routes.get("lobby").applyMembership(Long.MAX_VALUE, elsewhere.id(), 0, 1);
        h.routes.get("lobby").applyNeighbors(Long.MAX_VALUE, a.id(), new int[] {elsewhere.id()});
        h.clearAll();

        h.speak(a);

        assertEquals(1, audio(b).size());
        assertEquals(0, audio(elsewhere).size());
    }

    @Test
    void bridgeIgnoresEntriesForPlayersOnOtherServers() throws Exception {
        h = new RouterHarness();
        VoiceSession a = h.join("Alice", "lobby");
        VoiceSession elsewhere = h.join("Eve", "bedwars");
        h.setupServer("lobby", VoiceMode.FREE, a);
        h.membership("lobby", 0, 1, a, elsewhere);

        assertEquals(null, h.routes.get("lobby").route(elsewhere.id()));
    }

    @Test
    void proximityOnlyReachesNeighbours() throws Exception {
        h = new RouterHarness();
        VoiceSession a = h.join("Alice", "survival");
        VoiceSession near = h.join("Near", "survival");
        VoiceSession far = h.join("Far", "survival");
        h.setupServer("survival", VoiceMode.PROXIMITY, a, near, far);
        h.membership("survival", 0, 0, a, near, far);
        h.neighbors("survival", a, near);
        h.clearAll();

        h.speak(a);

        assertEquals(1, audio(near).size());
        assertEquals(AudioCategory.PROXIMITY, audio(near).get(0).category);
        assertEquals(0, audio(far).size());
    }

    @Test
    void teammateInRangeGetsTheFrameOnceAsGroup() throws Exception {
        h = new RouterHarness();
        VoiceSession a = h.join("Alice", "bw");
        VoiceSession mate = h.join("Mate", "bw");
        VoiceSession enemy = h.join("Enemy", "bw");
        h.setupServer("bw", VoiceMode.TEAM_PROXIMITY, a, mate, enemy);
        h.membership("bw", 0, 7, a, mate);
        h.membership("bw", 0, 8, enemy);
        h.neighbors("bw", a, mate, enemy);
        h.clearAll();

        h.speak(a);

        assertEquals(1, audio(mate).size());
        assertEquals(AudioCategory.GROUP, audio(mate).get(0).category);
        assertEquals(1, audio(enemy).size());
        assertEquals(AudioCategory.PROXIMITY, audio(enemy).get(0).category);
    }

    @Test
    void playerMuteBlocksAndTellsTheSpeaker() throws Exception {
        h = new RouterHarness();
        VoiceSession a = h.join("Alice", "lobby");
        VoiceSession b = h.join("Bob", "lobby");
        h.setupServer("lobby", VoiceMode.FREE, a, b);
        h.membership("lobby", 0, 1, a, b);
        h.mutes.mute(a.uuid(), "Alice", MuteScope.global(), 60_000, "spam", "Console");
        h.clearAll();

        h.speak(a);

        assertEquals(0, audio(b).size());
        List<ProxyToClient.MuteStatus> statuses = player(a).received(ProxyToClient.MuteStatus.class);
        assertEquals(1, statuses.size());
        assertTrue(statuses.get(0).muted);
        assertEquals(MuteKind.PLAYER, statuses.get(0).kind);
        assertEquals("spam", statuses.get(0).reason);

        // Expired: talking works again.
        h.clock.advance(61_000);
        h.speak(a);
        assertEquals(1, audio(b).size());
    }

    @Test
    void serverScopedMuteOnlyAppliesOnThatServer() throws Exception {
        h = new RouterHarness();
        VoiceSession a = h.join("Alice", "lobby");
        VoiceSession b = h.join("Bob", "lobby");
        h.setupServer("lobby", VoiceMode.FREE, a, b);
        h.membership("lobby", 0, 1, a, b);
        h.mutes.mute(a.uuid(), "Alice", MuteScope.server("bedwars"), 0, "", "Console");
        h.clearAll();

        h.speak(a);
        assertEquals(1, audio(b).size());

        h.mutes.mute(a.uuid(), "Alice", MuteScope.server("LOBBY"), 0, "", "Console");
        h.speak(a);
        assertEquals(1, audio(b).size(), "server names compare case-insensitively");
    }

    @Test
    void globalAndServerSwitchesRespectBypass() throws Exception {
        h = new RouterHarness();
        VoiceSession a = h.join("Alice", "lobby");
        VoiceSession admin = h.join("Admin", "lobby");
        VoiceSession b = h.join("Bob", "lobby");
        admin.setPermissions(0, false, true, true);
        h.setupServer("lobby", VoiceMode.FREE, a, admin, b);
        h.membership("lobby", 0, 1, a, admin, b);

        h.mutes.setGlobalMuteEnabled(true, "Console");
        h.clearAll();
        h.speak(a);
        h.speak(admin);
        assertEquals(1, audio(b).size());
        assertEquals(admin.id(), audio(b).get(0).speakerId);
        assertEquals(MuteKind.GLOBAL_WIDE, player(a).received(ProxyToClient.MuteStatus.class).get(0).kind);

        h.mutes.setGlobalMuteEnabled(false, "Console");
        h.mutes.setServerMuted("Lobby", true, "Console");
        h.clearAll();
        h.speak(a);
        h.speak(admin);
        assertEquals(1, audio(b).size());
        assertEquals(MuteKind.SERVER_WIDE, player(a).received(ProxyToClient.MuteStatus.class).get(0).kind);
    }

    @Test
    void deafenedAndCannotHearListenersAreSkipped() throws Exception {
        h = new RouterHarness();
        VoiceSession a = h.join("Alice", "lobby");
        VoiceSession deaf = h.join("Deaf", "lobby");
        VoiceSession dead = h.join("Dead", "lobby");
        VoiceSession b = h.join("Bob", "lobby");
        h.setupServer("lobby", VoiceMode.FREE, a, deaf, dead, b);
        h.membership("lobby", 0, 1, a, deaf, dead, b);
        deaf.setClientStatus(false, true);
        h.flags("lobby", dead, 0, PlayerFlags.CAN_SPEAK);
        h.clearAll();

        h.speak(a);

        assertEquals(0, audio(deaf).size());
        assertEquals(0, audio(dead).size());
        assertEquals(1, audio(b).size());
    }

    @Test
    void playersWhoMayNotSpeakAreSilent() throws Exception {
        h = new RouterHarness();
        VoiceSession spectator = h.join("Spec", "lobby");
        VoiceSession b = h.join("Bob", "lobby");
        h.setupServer("lobby", VoiceMode.FREE, spectator, b);
        h.membership("lobby", 0, 1, spectator, b);
        h.flags("lobby", spectator, 0, PlayerFlags.CAN_HEAR);
        h.clearAll();

        h.speak(spectator);
        assertEquals(0, audio(b).size());
    }

    @Test
    void disabledZoneIsSilent() throws Exception {
        h = new RouterHarness();
        VoiceSession a = h.join("Alice", "login");
        VoiceSession b = h.join("Bob", "login");
        h.setupServer("login", VoiceMode.DISABLED, a, b);
        h.membership("login", 0, 1, a, b);
        h.clearAll();

        h.speak(a);
        assertEquals(0, audio(b).size());
    }

    @Test
    void broadcastReachesTheRoomOnlyWithPermission() throws Exception {
        h = new RouterHarness();
        VoiceSession commander = h.join("Commander", "war");
        VoiceSession soldier = h.join("Soldier", "war");
        VoiceSession otherRoom = h.join("Other", "war");
        h.setupServer("war", VoiceMode.PROXIMITY, commander, soldier, otherRoom);
        h.membership("war", 1, 0, commander, soldier);
        h.membership("war", 2, 0, otherRoom);
        h.clearAll();

        h.speak(commander, true);
        assertEquals(0, audio(soldier).size(), "no permission: broadcast flag ignored, nobody in range");

        h.flags("war", commander, 100, PlayerFlags.DEFAULT | PlayerFlags.CAN_BROADCAST);
        h.speak(commander, true);
        assertEquals(1, audio(soldier).size());
        assertEquals(AudioCategory.BROADCAST, audio(soldier).get(0).category);
        assertEquals(100, audio(soldier).get(0).priority);
        assertEquals(0, audio(otherRoom).size(), "rooms are isolated");
    }

    @Test
    void streamCapPrefersHigherPriority() throws Exception {
        h = new RouterHarness("audio:\n  max-streams-per-listener: 1\n");
        VoiceSession listener = h.join("Listener", "lobby");
        VoiceSession first = h.join("First", "lobby");
        VoiceSession low = h.join("Low", "lobby");
        VoiceSession boss = h.join("Boss", "lobby");
        boss.setPermissions(200, false, false, false);
        h.setupServer("lobby", VoiceMode.FREE, listener, first, low, boss);
        h.membership("lobby", 0, 1, listener, first, low, boss);
        h.clearAll();

        h.speak(first);
        h.speak(low);
        h.speak(boss);

        List<VoiceAudio> heard = audio(listener);
        assertEquals(2, heard.size());
        assertEquals(first.id(), heard.get(0).speakerId);
        assertEquals(boss.id(), heard.get(1).speakerId);

        // After the active speaker goes quiet for a moment, the slot frees up.
        h.clock.advance(1_000);
        h.speak(low);
        assertEquals(3, audio(listener).size());
    }

    @Test
    void serversWithoutPluginFollowTheFallback() throws Exception {
        h = new RouterHarness();
        VoiceSession a = h.join("Alice", "vanilla");
        VoiceSession b = h.join("Bob", "vanilla");
        h.speak(a);
        assertEquals(0, audio(b).size(), "default fallback is DISABLED");
        h.close();

        h = new RouterHarness("servers-without-plugin: FREE\n");
        a = h.join("Alice", "vanilla");
        b = h.join("Bob", "vanilla");
        VoiceSession other = h.join("Other", "elsewhere");
        h.speak(a);
        assertEquals(1, audio(b).size());
        assertEquals(0, audio(other).size());
        assertEquals(VoiceMode.FREE, h.router.voiceStateFor(a).mode);
    }

    @Test
    void playerInfoIsSentOnceBeforeTheFirstFrame() throws Exception {
        h = new RouterHarness();
        VoiceSession a = h.join("Alice", "lobby");
        VoiceSession b = h.join("Bob", "lobby");
        h.setupServer("lobby", VoiceMode.FREE, a, b);
        h.membership("lobby", 0, 1, a, b);
        h.clearAll();

        h.speak(a);
        h.speak(a);

        List<?> packets = player(b).toClient;
        assertEquals(3, packets.size());
        ProxyToClient.PlayerInfo info = (ProxyToClient.PlayerInfo) packets.get(0);
        assertEquals(a.id(), info.entries.get(0).speakerId);
        assertEquals("Alice", info.entries.get(0).name);
        assertTrue(packets.get(1) instanceof VoiceAudio);
        assertTrue(packets.get(2) instanceof VoiceAudio);
    }

    @Test
    void micEndOnlyGoesToActiveListeners() throws Exception {
        h = new RouterHarness();
        VoiceSession a = h.join("Alice", "lobby");
        VoiceSession b = h.join("Bob", "lobby");
        VoiceSession late = h.join("Late", "lobby");
        h.setupServer("lobby", VoiceMode.FREE, a, b, late);
        h.membership("lobby", 0, 1, a, b);
        h.speak(a);
        h.membership("lobby", 0, 1, late);
        h.clearAll();

        h.router.onMicEnd(a, new ClientToProxy.MicEnd(99));

        assertEquals(1, player(b).received(ProxyToClient.VoiceEnd.class).size());
        assertEquals(0, player(late).received(ProxyToClient.VoiceEnd.class).size());
    }

    @Test
    void voiceStateReflectsZoneAndFlags() throws Exception {
        h = new RouterHarness();
        VoiceSession a = h.join("Alice", "bw");
        h.setupServer("bw", VoiceMode.TEAM, a);
        h.flags("bw", a, 50, PlayerFlags.DEFAULT | PlayerFlags.CAN_BROADCAST);

        ProxyToClient.VoiceState state = h.router.voiceStateFor(a);
        assertEquals("bw", state.server);
        assertEquals(VoiceMode.TEAM, state.mode);
        assertEquals(50, state.priority);
        assertTrue(state.canSpeak);
        assertTrue(state.canBroadcast);
        assertEquals(16000, state.bitrate);

        List<ProxyToClient.VoiceState> sent = player(a).received(ProxyToClient.VoiceState.class);
        assertTrue(sent.size() >= 1, "zone/flag changes push the state to the client");
        assertEquals(VoiceMode.TEAM, sent.get(sent.size() - 1).mode);
    }
}
