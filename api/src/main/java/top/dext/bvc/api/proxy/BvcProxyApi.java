package top.dext.bvc.api.proxy;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import top.dext.bvc.api.MuteService;

/**
 * Entry point for plugins running on the proxy (Velocity or BungeeCord).
 */
public interface BvcProxyApi {

    MuteService mutes();

    /** Voice state of an online player, if they are online. */
    Optional<VoicePlayer> player(UUID uuid);

    Collection<VoicePlayer> players();
}
