package top.dext.bvc.backend;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Team;

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
final class VoiceEngine {

    private static final long PERMISSION_REFRESH_MILLIS = 5_000;

    private final ProxyLink link;
    private final Logger logger;
    final Map<String, ZoneSettings> rooms = new ConcurrentHashMap<String, ZoneSettings>();
    final Map<UUID, PlayerOverrides> overrides = new ConcurrentHashMap<UUID, PlayerOverrides>();
    private final Map<UUID, PlayerSnapshot> snapshots = new ConcurrentHashMap<UUID, PlayerSnapshot>();
    private final Map<UUID, CachedPermissions> permissions = new ConcurrentHashMap<UUID, CachedPermissions>();
    private final AtomicBoolean scoreboardWarned = new AtomicBoolean();

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

    VoiceEngine(ProxyLink link, Logger logger, BackendSettings settings) {
        this.link = link;
        this.logger = logger;
        this.settings = settings;
        this.rules = settings.rules;
    }

    void start() {
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

    void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    /** New config: rules from the file replace any runtime default-zone change. */
    void apply(BackendSettings newSettings) {
        settings = newSettings;
        rules = newSettings.rules;
        permissions.clear();
        link.requestResync();
    }

    VoiceRules rules() {
        return rules;
    }

    void setDefaultZone(ZoneSettings zone) {
        rules = rules.withDefaultZone(zone);
    }

    BackendSettings settings() {
        return settings;
    }

    // ------------------------------------------------------------------ game threads

    /** Called on the thread that owns {@code player}. */
    void sample(Player player) {
        UUID uuid = player.getUniqueId();
        Location location = player.getLocation();
        if (location.getWorld() == null) {
            return;
        }
        String team = rules.scoreboardTeams ? teamOf(player) : null;
        CachedPermissions perms = permissionsOf(player);
        snapshots.put(uuid, new PlayerSnapshot(0, uuid, location.getWorld().getName(), location.getX(), location.getY(),
                location.getZ(), player.getGameMode() == GameMode.SPECTATOR, team, perms.priority, perms.broadcast));
        sampledAt.put(uuid, System.currentTimeMillis());
    }

    PlayerSnapshot snapshotOf(UUID uuid) {
        return snapshots.get(uuid);
    }

    /** Millis since the player was last sampled, or -1. */
    long sampleAge(UUID uuid) {
        Long at = sampledAt.get(uuid);
        return at == null ? -1 : System.currentTimeMillis() - at;
    }

    /** Millis since the planner last ran, or -1. */
    long tickAge() {
        return lastTickAt == 0 ? -1 : System.currentTimeMillis() - lastTickAt;
    }

    /** Duration of the last planner pass in microseconds. */
    long lastTickMicros() {
        return lastTickNanos / 1000;
    }

    private String teamOf(Player player) {
        try {
            // The player's own scoreboard first: minigames usually give each player one.
            Team team = player.getScoreboard().getEntryTeam(player.getName());
            if (team == null) {
                team = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(player.getName());
            }
            return team == null ? null : team.getName();
        } catch (RuntimeException e) {
            // Folia restricts scoreboard access off the global thread.
            if (scoreboardWarned.compareAndSet(false, true)) {
                logger.warning("Scoreboard teams are not readable here (" + e + "); set groups through the API instead");
            }
            return null;
        }
    }

    private CachedPermissions permissionsOf(Player player) {
        long now = System.currentTimeMillis();
        CachedPermissions cached = permissions.get(player.getUniqueId());
        if (cached != null && now - cached.at < PERMISSION_REFRESH_MILLIS) {
            return cached;
        }
        int priority = 0;
        for (int level : settings.priorityLevels) {
            if (player.hasPermission("bvc.priority." + level)) {
                priority = level;
                break;
            }
        }
        cached = new CachedPermissions(priority, player.hasPermission("bvc.broadcast"), now);
        permissions.put(player.getUniqueId(), cached);
        return cached;
    }

    void forget(UUID uuid) {
        snapshots.remove(uuid);
        sampledAt.remove(uuid);
        permissions.remove(uuid);
        overrides.remove(uuid);
    }

    // ------------------------------------------------------------------ planner thread

    void tick() {
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
            logger.log(Level.SEVERE, "Voice planning failed", error);
        } finally {
            lastTickAt = System.currentTimeMillis();
            lastTickNanos = System.nanoTime() - started;
        }
    }

    /** Latest decision for a player, or {@code null}. */
    PlayerPlan planOf(UUID uuid) {
        for (PlayerPlan plan : latest.values()) {
            if (plan.uuid.equals(uuid)) {
                return plan;
            }
        }
        return null;
    }

    PlayerPlan planOf(int id) {
        return latest.get(id);
    }

    int plannedCount() {
        return latest.size();
    }
}
