package top.dext.bvc.backend.voice;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import top.dext.bvc.api.server.ZoneSettings;

/**
 * Configured voice rules of the sub-server. Immutable; reloading builds a new instance.
 */
public final class VoiceRules {

    public final ZoneSettings defaultZone;
    /** World name -&gt; zone. A world listed here is its own isolated zone. */
    public final Map<String, ZoneSettings> worldZones;
    public final SpectatorPolicy spectators;
    /** Use main-scoreboard teams for team modes (API groups always win). */
    public final boolean scoreboardTeams;

    public VoiceRules(ZoneSettings defaultZone, Map<String, ZoneSettings> worldZones, SpectatorPolicy spectators,
                      boolean scoreboardTeams) {
        this.defaultZone = defaultZone;
        this.worldZones = Collections.unmodifiableMap(new LinkedHashMap<String, ZoneSettings>(worldZones));
        this.spectators = spectators;
        this.scoreboardTeams = scoreboardTeams;
    }

    public VoiceRules withDefaultZone(ZoneSettings zone) {
        return new VoiceRules(zone, worldZones, spectators, scoreboardTeams);
    }
}
