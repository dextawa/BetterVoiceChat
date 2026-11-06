package top.dext.bvc.backend.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import top.dext.bvc.api.server.ZoneSettings;
import top.dext.bvc.backend.voice.PlayerOverrides;
import top.dext.bvc.backend.voice.PlayerPlan;
import top.dext.bvc.backend.voice.PlayerSnapshot;
import top.dext.bvc.backend.voice.TableEmitter;
import top.dext.bvc.backend.voice.VoicePlanner;
import top.dext.bvc.backend.voice.VoiceRules;
import top.dext.bvc.protocol.packet.BackendToProxy;
import top.dext.bvc.protocol.packet.Packet;

/**
 * Samples players on their owning thread, plans on a dedicated thread, sends the changes to the
 * proxy. The game threads only pay for reading a few fields per player.
 */
public final class VoiceEngine {

    private static final long PERMISSION_REFRESH_MILLIS = 5_000;

    private final ProxyLink link;
    private final BackendLog logger;
    public final Map<String, ZoneSettings> rooms = new ConcurrentHashMap<String, ZoneSettings>();
    public final Map<UUID, PlayerOverrides> overrides = new ConcurrentHashMap<UUID, PlayerOverrides>();
    private final Map<UUID, PlayerSnapshot> snapshots = new ConcurrentHashMap<UUID, PlayerSnapshot>();
    private final Map<UUID, CachedPermissions> permissions = new ConcurrentHashMap<UUID, CachedPermissions>();

    // Planner-thread state.
    private final VoicePlanner planner = new VoicePlanner();
    private final TableEmitter emitter = new TableEmitter(System.currentTimeMillis() * 1000L);
    private Map<Integer, PlayerPlan> sent = Collections.emptyMap();
    private long lastFull;

    private volatile BackendSettings settings;
    private volatile VoiceRules rules;
    private volatile Map<Integer, PlayerPlan> latest = Collections.emptyMap();
    private volatile long lastTickAt;
    private volatile long lastTickNanos;
    private final Map<UUID, Long> sampledAt = new ConcurrentHashMap<UUID, Long>();
    private ScheduledExecutorService executor;

    private static final class CachedPermissions {
        final int priority;
        final boolean broadcast;
        final long at;

        CachedPermissions(int priority, boolean broadcast, long at) {
            this.priority = priority;
            this.broadcast = broadcast;
            this.at = at;
        }
    }

    public VoiceEngine(ProxyLink link, BackendLog logger, BackendSettings settings) {
        this.link = link;
        this.logger = logger;
        this.settings = settings;
        this.rules = settings.rules;
    }

    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "BVC-Planner");
                thread.setDaemon(true);
                return thread;
            }
        });
        long period = settings.intervalTicks * 50L;
        executor.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                tick();
            }
        }, period, period, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** New config: rules from the file replace any runtime default-zone change. */
    public void apply(BackendSettings newSettings) {
        settings = newSettings;
        rules = newSettings.rules;
        permissions.clear();
        link.requestResync();
    }

    public VoiceRules rules() {
        return rules;
    }

    public void setDefaultZone(ZoneSettings zone) {
        rules = rules.withDefaultZone(zone);
    }

    public BackendSettings settings() {
        return settings;
    }

    // ------------------------------------------------------------------ game threads

    /** Whether {@link #sample} wants the player's scoreboard team; skip the lookup otherwise. */
    public boolean wantsTeams() {
        return rules.scoreboardTeams;
    }

    /**
     * Called on the thread that owns the player, with what the platform read from it.
     *
     * @param team        scoreboard team name, or {@code null}
     * @param permissions checks a permission node; only called every few seconds
     */
    public void sample(UUID uuid, String world, double x, double y, double z, boolean spectator, String team,
                       Predicate<String> permissions) {
        CachedPermissions perms = permissionsOf(uuid, permissions);
        snapshots.put(uuid, new PlayerSnapshot(0, uuid, world, x, y, z, spectator, team, perms.priority, perms.broadcast));
        sampledAt.put(uuid, System.currentTimeMillis());
    }

    public PlayerSnapshot snapshotOf(UUID uuid) {
        return snapshots.get(uuid);
    }

    /** Millis since the player was last sampled, or -1. */
    public long sampleAge(UUID uuid) {
        Long at = sampledAt.get(uuid);
        return at == null ? -1 : System.currentTimeMillis() - at;
    }

    /** Millis since the planner last ran, or -1. */
    public long tickAge() {
        return lastTickAt == 0 ? -1 : System.currentTimeMillis() - lastTickAt;
    }

    /** Duration of the last planner pass in microseconds. */
    public long lastTickMicros() {
        return lastTickNanos / 1000;
    }

    private CachedPermissions permissionsOf(UUID uuid, Predicate<String> check) {
        long now = System.currentTimeMillis();
        CachedPermissions cached = permissions.get(uuid);
        if (cached != null && now - cached.at < PERMISSION_REFRESH_MILLIS) {
            return cached;
        }
        int priority = 0;
        for (int level : settings.priorityLevels) {
            if (check.test("bvc.priority." + level)) {
                priority = level;
                break;
            }
        }
        cached = new CachedPermissions(priority, check.test("bvc.broadcast"), now);
        permissions.put(uuid, cached);
        return cached;
    }

    public void forget(UUID uuid) {
        snapshots.remove(uuid);
        sampledAt.remove(uuid);
        permissions.remove(uuid);
        overrides.remove(uuid);
    }

    // ------------------------------------------------------------------ planner thread

    private void tick() {
        long started = System.nanoTime();
        try {
            long now = System.currentTimeMillis();
            boolean full = link.consumeResyncRequest() || now - lastFull >= settings.fullResyncSeconds * 1000L;

            List<PlayerSnapshot> players = new ArrayList<PlayerSnapshot>(snapshots.size());
            for (PlayerSnapshot snapshot : snapshots.values()) {
                ProxyLink.Binding binding = link.binding(snapshot.uuid);
                if (binding != null && binding.voiceEnabled && link.isOnline(snapshot.uuid)) {
                    players.add(snapshot.withId(binding.id));
                }
            }
            Map<Integer, PlayerPlan> plans = planner.plan(players, rules, rooms, new VoicePlanner.OverrideSource() {
                @Override
                public PlayerOverrides get(UUID player) {
                    return overrides.get(player);
                }
            });
            latest = plans;

            List<Packet<BackendToProxy.Handler>> packets = emitter.diff(sent, plans, full);
            for (Packet<BackendToProxy.Handler> packet : packets) {
                if (!link.send(packet)) {
                    return; // nobody online to carry it; retry the same diff next time
                }
            }
            sent = plans;
            if (full) {
                lastFull = now;
            }
        } catch (Throwable error) {
            logger.error("Voice planning failed", error);
        } finally {
            lastTickAt = System.currentTimeMillis();
            lastTickNanos = System.nanoTime() - started;
        }
    }

    /** Latest decision for a player, or {@code null}. */
    public PlayerPlan planOf(UUID uuid) {
        for (PlayerPlan plan : latest.values()) {
            if (plan.uuid.equals(uuid)) {
                return plan;
            }
        }
        return null;
    }

    public PlayerPlan planOf(int id) {
        return latest.get(id);
    }

    public int plannedCount() {
        return latest.size();
    }
}
