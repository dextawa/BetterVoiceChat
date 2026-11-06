package top.dext.bvc.backend.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import top.dext.bvc.api.server.BvcServer;
import top.dext.bvc.api.server.BvcServerApi;
import top.dext.bvc.api.server.VoiceMode;
import top.dext.bvc.api.server.VoiceStateListener;
import top.dext.bvc.api.server.ZoneSettings;
import top.dext.bvc.protocol.packet.ProxyToBackend;

/**
 * The public sub-server API on top of {@link BvcBackend}, with a fake platform.
 */
class ServerApiTest {

    @TempDir
    Path data;

    private BvcBackend backend;

    private static final class Player implements BackendPlayer {
        final UUID uuid = UUID.randomUUID();
        final String name;

        Player(String name) {
            this.name = name;
        }

        @Override
        public UUID uuid() {
            return uuid;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isOnline() {
            return true;
        }

        @Override
        public boolean listensOn(String channel) {
            return true;
        }

        @Override
        public void sendPluginMessage(String channel, byte[] data) {
        }
    }

    private void start() {
        backend = new BvcBackend(new BackendPlatform() {
            @Override
            public BackendLog log() {
                return new BackendLog() {
                    @Override
                    public void info(String message) {
                    }

                    @Override
                    public void warn(String message) {
                    }

                    @Override
                    public void error(String message, Throwable error) {
                        throw new AssertionError(message, error);
                    }
                };
            }

            @Override
            public String pluginVersion() {
                return "test";
            }

            @Override
            public String software() {
                return "Test";
            }

            @Override
            public Path dataFolder() {
                return data;
            }

            @Override
            public UUID playerByName(String name) {
                return null;
            }

            @Override
            public String nameOf(UUID uuid) {
                return null;
            }

            @Override
            public Collection<String> onlinePlayerNames() {
                return Collections.emptyList();
            }
        });
        backend.start();
    }

    @AfterEach
    void stop() {
        if (backend != null) {
            backend.stop();
        }
    }

    private void bind(Player player, int id, boolean voice) {
        backend.onProxyMessage(player, ProxyToBackend.REGISTRY.encode(new ProxyToBackend.BindPlayers(
                List.of(new ProxyToBackend.BindPlayers.Entry(player.uuid, id, voice)))));
    }

    @Test
    void roomsGroupsAndPriorityCanBeReadBack() {
        start();
        BvcServerApi api = BvcServer.get();
        Player a = new Player("A");
        Player b = new Player("B");
        backend.onJoin(a);
        backend.onJoin(b);
        api.createRoom("arena", ZoneSettings.of(VoiceMode.TEAM));
        api.setRoom(a.uuid, "arena");
        api.setRoom(b.uuid, "arena");
        api.setGroup(a.uuid, "red");
        api.setPriority(b.uuid, 300);

        assertEquals(Set.of(a.uuid, b.uuid), api.membersOf("arena"));
        assertEquals("red", api.groupOf(a.uuid).orElseThrow());
        assertTrue(api.groupOf(b.uuid).isEmpty());
        assertEquals(255, api.priorityOf(b.uuid));

        backend.onQuit(b.uuid);
        assertEquals(Set.of(a.uuid), api.membersOf("arena"));
        api.deleteRoom("arena");
        assertTrue(api.membersOf("arena").isEmpty());
        assertTrue(api.roomOf(a.uuid).isEmpty());
        assertEquals("red", api.groupOf(a.uuid).orElseThrow(), "the group survives the room");
    }

    @Test
    void voiceListenersFollowTheProxyBindings() {
        start();
        BvcServerApi api = BvcServer.get();
        List<String> events = new ArrayList<>();
        VoiceStateListener listener = new VoiceStateListener() {
            @Override
            public void onVoiceChanged(UUID player, boolean hasVoice) {
                events.add(hasVoice ? "on" : "off");
            }
        };
        api.addListener(listener);
        Player a = new Player("A");
        backend.onJoin(a);

        bind(a, 1, false);   // joined without voice: no event
        bind(a, 1, true);    // voice came up
        bind(a, 1, true);    // unchanged
        assertTrue(api.hasVoice(a.uuid));
        backend.onProxyMessage(a, ProxyToBackend.REGISTRY.encode(new ProxyToBackend.UnbindPlayers(new int[] {1})));
        bind(a, 2, true);
        backend.onQuit(a.uuid);

        assertEquals(List.of("on", "off", "on", "off"), events);
        api.removeListener(listener);
        backend.onJoin(a);
        bind(a, 3, true);
        assertEquals(4, events.size());
    }
}
