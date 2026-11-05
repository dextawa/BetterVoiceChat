package top.dext.bvc.backend;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import top.dext.bvc.api.MuteScope;
import top.dext.bvc.api.server.BvcServerApi;
import top.dext.bvc.api.server.ZoneSettings;
import top.dext.bvc.backend.voice.PlayerOverrides;
import top.dext.bvc.protocol.packet.BackendToProxy;

/**
 * {@link BvcServerApi} for minigames. Every call only updates maps; the planner picks the
 * change up on its next pass (within {@code update-interval-ticks}).
 */
final class BukkitVoiceApi implements BvcServerApi {

    private final VoiceEngine engine;
    private final ProxyLink link;

    BukkitVoiceApi(VoiceEngine engine, ProxyLink link) {
        this.engine = engine;
        this.link = link;
    }

    @Override
    public ZoneSettings defaultZone() {
        return engine.rules().defaultZone;
    }

    @Override
    public void setDefaultZone(ZoneSettings settings) {
        engine.setDefaultZone(requireZone(settings));
    }

    @Override
    public void createRoom(String name, ZoneSettings settings) {
        engine.rooms.put(requireName(name), requireZone(settings));
    }

    @Override
    public Optional<ZoneSettings> room(String name) {
        return name == null ? Optional.<ZoneSettings>empty() : Optional.ofNullable(engine.rooms.get(name));
    }

    @Override
    public Set<String> rooms() {
        return new TreeSet<String>(engine.rooms.keySet());
    }

    @Override
    public void deleteRoom(String name) {
        if (name == null || engine.rooms.remove(name) == null) {
            return;
        }
        for (Map.Entry<UUID, PlayerOverrides> entry : engine.overrides.entrySet()) {
            if (name.equals(entry.getValue().room)) {
                update(entry.getKey(), entry.getValue().withRoom(null));
            }
        }
    }

    @Override
    public void setRoom(UUID player, String room) {
        update(player, current(player).withRoom(room));
    }

    @Override
    public Optional<String> roomOf(UUID player) {
        PlayerOverrides overrides = engine.overrides.get(player);
        String room = overrides == null ? null : overrides.room;
        return room != null && engine.rooms.containsKey(room) ? Optional.of(room) : Optional.<String>empty();
    }

    @Override
    public void setGroup(UUID player, String group) {
        update(player, current(player).withGroup(group));
    }

    @Override
    public void setPriority(UUID player, int priority) {
        update(player, current(player).withPriority(Math.max(0, Math.min(255, priority))));
    }

    @Override
    public void setCanSpeak(UUID player, boolean canSpeak) {
        update(player, current(player).withCanSpeak(canSpeak));
    }

    @Override
    public void setCanHear(UUID player, boolean canHear) {
        update(player, current(player).withCanHear(canHear));
    }

    @Override
    public void setCanBroadcast(UUID player, boolean canBroadcast) {
        update(player, current(player).withCanBroadcast(canBroadcast));
    }

    @Override
    public void reset(UUID player) {
        engine.overrides.remove(player);
    }

    @Override
    public boolean hasVoice(UUID player) {
        ProxyLink.Binding binding = link.binding(player);
        return binding != null && binding.voiceEnabled;
    }

    @Override
    public void mute(UUID player, MuteScope scope, long durationMillis, String reason, String operator) {
        link.send(new BackendToProxy.MuteRequest(player, scopeKey(scope), Math.max(0, durationMillis),
                reason == null ? "" : reason, operator == null ? "" : operator));
    }

    @Override
    public void unmute(UUID player, MuteScope scope, String operator) {
        link.send(new BackendToProxy.MuteRequest(player, scopeKey(scope), BackendToProxy.MuteRequest.UNMUTE, "",
                operator == null ? "" : operator));
    }

    /** {@code null} means "this sub-server", which only the proxy can name. */
    private static String scopeKey(MuteScope scope) {
        return scope == null ? "" : scope.key();
    }

    private PlayerOverrides current(UUID player) {
        PlayerOverrides overrides = engine.overrides.get(player);
        return overrides == null ? PlayerOverrides.NONE : overrides;
    }

    private void update(UUID player, PlayerOverrides overrides) {
        if (overrides.room == null && overrides.group == null && overrides.priority == 0 && overrides.canSpeak == null
                && overrides.canHear == null && overrides.canBroadcast == null) {
            engine.overrides.remove(player);
        } else {
            engine.overrides.put(player, overrides);
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Room name must not be empty");
        }
        return name;
    }

    private static ZoneSettings requireZone(ZoneSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("Zone settings must not be null");
        }
        return settings;
    }
}
