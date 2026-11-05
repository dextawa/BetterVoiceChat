package top.dext.bvc.api;

import java.util.Objects;
import java.util.UUID;

/**
 * A voice mute record. Immutable.
 */
public final class Mute {

    private final long id;
    private final UUID target;
    private final String targetName;
    private final MuteScope scope;
    private final String reason;
    private final String operator;
    private final long createdAt;
    private final long expiresAt;

    public Mute(long id, UUID target, String targetName, MuteScope scope, String reason, String operator,
                long createdAt, long expiresAt) {
        this.id = id;
        this.target = Objects.requireNonNull(target, "target");
        this.targetName = targetName == null ? "" : targetName;
        this.scope = Objects.requireNonNull(scope, "scope");
        this.reason = reason == null ? "" : reason;
        this.operator = operator == null ? "" : operator;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    /** Database id, or 0 if not persisted yet. */
    public long id() {
        return id;
    }

    public UUID target() {
        return target;
    }

    public String targetName() {
        return targetName;
    }

    public MuteScope scope() {
        return scope;
    }

    public String reason() {
        return reason;
    }

    /** Who issued it: a player name, "Console", or a plugin such as "LiteBans". */
    public String operator() {
        return operator;
    }

    public long createdAt() {
        return createdAt;
    }

    /** Epoch millis when the mute ends, or 0 for permanent. */
    public long expiresAt() {
        return expiresAt;
    }

    public boolean isPermanent() {
        return expiresAt == 0;
    }

    public boolean isActive(long now) {
        return expiresAt == 0 || expiresAt > now;
    }

    public Mute withId(long newId) {
        return new Mute(newId, target, targetName, scope, reason, operator, createdAt, expiresAt);
    }

    @Override
    public String toString() {
        return "Mute{" + targetName + "/" + target + " scope=" + scope + " expiresAt=" + expiresAt + " reason=" + reason + "}";
    }
}
