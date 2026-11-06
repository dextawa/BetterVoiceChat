package top.dext.bvc.proxy.recording;

import java.util.UUID;

/**
 * One recorded Opus frame (20 ms) of one speaker.
 *
 * @param at wall-clock millis when the proxy forwarded it
 */
public record Frame(UUID speaker, long at, byte[] opus) {
}
