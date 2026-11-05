package top.dext.bvc.proxy.mute;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import top.dext.bvc.api.Mute;
import top.dext.bvc.api.MuteListener;
import top.dext.bvc.api.MuteScope;
import top.dext.bvc.api.MuteService;
import top.dext.bvc.protocol.data.MuteKind;
import top.dext.bvc.protocol.packet.ProxyToClient;
import top.dext.bvc.proxy.platform.BvcLogger;
import top.dext.bvc.proxy.session.VoiceSession;
import top.dext.bvc.proxy.storage.Stores;

/**
 * Mutes enforced by the proxy. Active mutes live in memory (the router checks them for every
 * frame); every change is written through to the database.
 */
public final class MuteManager implements MuteService {

    private static final String SETTING_GLOBAL = "mute.global";
    private static final String SETTING_SERVERS = "mute.servers";

    private final Stores stores;
    private final Clock clock;
    private final BvcLogger logger;
    private final Consumer<UUID> onPlayerChanged;
    private final Runnable onSwitchChanged;

    /** Copy-on-write lists: readers on network threads never lock. */
    private final ConcurrentHashMap<UUID, List<Mute>> active = new ConcurrentHashMap<>();
    private final Set<String> mutedServers = ConcurrentHashMap.newKeySet();
    private final List<MuteListener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean globalMute;

    /**
     * @param onPlayerChanged called with the target after their own mutes changed
     * @param onSwitchChanged called after the global or a server-wide switch changed
     */
    public MuteManager(Stores stores, Clock clock, BvcLogger logger, Consumer<UUID> onPlayerChanged, Runnable onSwitchChanged) {
        this.stores = stores;
        this.clock = clock;
        this.logger = logger;
        this.onPlayerChanged = onPlayerChanged;
        this.onSwitchChanged = onSwitchChanged;
    }

    /** Loads active mutes and switches from the database. Call once at startup. */
    public void load() throws java.sql.SQLException {
        long now = clock.millis();
        for (Mute mute : stores.loadActiveMutesSync(now)) {
            active.merge(mute.target(), List.of(mute), MuteManager::concat);
        }
        globalMute = stores.getSettingSync(SETTING_GLOBAL).map(Boolean::parseBoolean).orElse(false);
        stores.getSettingSync(SETTING_SERVERS).ifPresent(value -> {
            for (String server : value.split(",")) {
                if (!server.isBlank()) {
                    mutedServers.add(server.trim());
                }
            }
        });
        logger.info("Loaded " + active.values().stream().mapToInt(List::size).sum() + " active voice mutes");
    }

    // ------------------------------------------------------------------ MuteService

    @Override
    public Mute mute(UUID target, String targetName, MuteScope scope, long durationMillis, String reason, String operator) {
        long now = clock.millis();
        Mute mute = new Mute(0, target, targetName, scope, reason, operator, now, durationMillis > 0 ? now + durationMillis : 0);
        active.compute(target, (key, list) -> {
            List<Mute> next = new ArrayList<>();
            if (list != null) {
                for (Mute existing : list) {
                    if (!existing.scope().equals(scope)) {
                        next.add(existing);
                    }
                }
            }
            next.add(mute);
            return List.copyOf(next);
        });
        // Same database thread, so the deactivate always runs before the insert.
        stores.deactivateMutes(target, scope, operator, now);
        stores.insertMute(mute).thenAccept(saved -> replaceCached(saved)).exceptionally(error -> {
            logger.error("Could not save mute of " + targetName, error);
            return null;
        });
        for (MuteListener listener : listeners) {
            safely(() -> listener.onMute(mute));
        }
        onPlayerChanged.accept(target);
        return mute;
    }

    @Override
    public boolean unmute(UUID target, MuteScope scope, String operator) {
        long now = clock.millis();
        List<Mute> removed = new ArrayList<>();
        active.computeIfPresent(target, (key, list) -> {
            List<Mute> next = new ArrayList<>();
            for (Mute existing : list) {
                if (existing.scope().equals(scope)) {
                    removed.add(existing);
                } else {
                    next.add(existing);
                }
            }
            return next.isEmpty() ? null : List.copyOf(next);
        });
        stores.deactivateMutes(target, scope, operator, now);
        for (Mute mute : removed) {
            for (MuteListener listener : listeners) {
                safely(() -> listener.onUnmute(mute, operator));
            }
        }
        if (!removed.isEmpty()) {
            onPlayerChanged.accept(target);
        }
        return removed.stream().anyMatch(mute -> mute.isActive(now));
    }

    @Override
    public Optional<Mute> activeMute(UUID target, String server) {
        long now = clock.millis();
        List<Mute> list = active.getOrDefault(target, List.of());
        Mute found = null;
        for (Mute mute : list) {
            if (mute.isActive(now) && mute.scope().covers(server)) {
                if (mute.scope().isGlobal()) {
                    return Optional.of(mute);
                }
                found = mute;
            }
        }
        return Optional.ofNullable(found);
    }

    @Override
    public List<Mute> activeMutes(UUID target) {
        long now = clock.millis();
        List<Mute> result = new ArrayList<>();
        for (Mute mute : active.getOrDefault(target, List.of())) {
            if (mute.isActive(now)) {
                result.add(mute);
            }
        }
        return Collections.unmodifiableList(result);
    }

    @Override
    public boolean isGlobalMuteEnabled() {
        return globalMute;
    }

    @Override
    public void setGlobalMuteEnabled(boolean enabled, String operator) {
        if (globalMute == enabled) {
            return;
        }
        globalMute = enabled;
        stores.setSetting(SETTING_GLOBAL, Boolean.toString(enabled));
        for (MuteListener listener : listeners) {
            safely(() -> listener.onGlobalMuteChanged(enabled, operator));
        }
        onSwitchChanged.run();
    }

    @Override
    public boolean isServerMuted(String server) {
        return server != null && mutedServers.contains(server.toLowerCase(Locale.ROOT));
    }

    @Override
    public void setServerMuted(String server, boolean muted, String operator) {
        String key = server.toLowerCase(Locale.ROOT);
        boolean changed = muted ? mutedServers.add(key) : mutedServers.remove(key);
        if (!changed) {
            return;
        }
        stores.setSetting(SETTING_SERVERS, String.join(",", mutedServers));
        for (MuteListener listener : listeners) {
            safely(() -> listener.onServerMuteChanged(server, muted, operator));
        }
        onSwitchChanged.run();
    }

    public Set<String> mutedServers() {
        return Collections.unmodifiableSet(mutedServers);
    }

    @Override
    public void addListener(MuteListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(MuteListener listener) {
        listeners.remove(listener);
    }

    // ------------------------------------------------------------------ enforcement

    /**
     * Why {@code session} may not speak on {@code server} right now, or {@code null} if they may.
     * Hot path: called for every audio frame.
     */
    public ProxyToClient.MuteStatus check(VoiceSession session, String server, long now) {
        if (globalMute && !session.bypassGlobalMute()) {
            return new ProxyToClient.MuteStatus(true, MuteKind.GLOBAL_WIDE, MuteScope.GLOBAL_KEY, "", 0L);
        }
        if (!mutedServers.isEmpty() && !session.bypassServerMute() && mutedServers.contains(server.toLowerCase(Locale.ROOT))) {
            return new ProxyToClient.MuteStatus(true, MuteKind.SERVER_WIDE, server, "", 0L);
        }
        List<Mute> list = active.get(session.uuid());
        if (list == null) {
            return null;
        }
        Mute serverMute = null;
        for (Mute mute : list) {
            if (mute.isActive(now) && mute.scope().covers(server)) {
                if (mute.scope().isGlobal()) {
                    return status(mute);
                }
                serverMute = mute;
            }
        }
        return serverMute == null ? null : status(serverMute);
    }

    private static ProxyToClient.MuteStatus status(Mute mute) {
        return new ProxyToClient.MuteStatus(true, MuteKind.PLAYER, mute.scope().key(), mute.reason(), mute.expiresAt());
    }

    /** Drops expired mutes from memory and notifies affected players. Run every few seconds. */
    public void purgeExpired() {
        long now = clock.millis();
        for (Map.Entry<UUID, List<Mute>> entry : active.entrySet()) {
            List<Mute> list = entry.getValue();
            boolean anyExpired = false;
            for (Mute mute : list) {
                if (!mute.isActive(now)) {
                    anyExpired = true;
                    break;
                }
            }
            if (!anyExpired) {
                continue;
            }
            List<Mute> expired = new ArrayList<>();
            active.computeIfPresent(entry.getKey(), (key, current) -> {
                List<Mute> next = new ArrayList<>();
                for (Mute mute : current) {
                    (mute.isActive(now) ? next : expired).add(mute);
                }
                return next.isEmpty() ? null : List.copyOf(next);
            });
            for (Mute mute : expired) {
                for (MuteListener listener : listeners) {
                    safely(() -> listener.onUnmute(mute, "expired"));
                }
            }
            if (!expired.isEmpty()) {
                onPlayerChanged.accept(entry.getKey());
            }
        }
    }

    private void replaceCached(Mute saved) {
        active.computeIfPresent(saved.target(), (key, list) -> {
            List<Mute> next = new ArrayList<>(list.size());
            for (Mute mute : list) {
                next.add(mute.id() == 0 && mute.scope().equals(saved.scope()) && mute.createdAt() == saved.createdAt() ? saved : mute);
            }
            return List.copyOf(next);
        });
    }

    private void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            logger.warn("A mute listener failed", e);
        }
    }

    private static List<Mute> concat(List<Mute> a, List<Mute> b) {
        List<Mute> all = new ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }
}
