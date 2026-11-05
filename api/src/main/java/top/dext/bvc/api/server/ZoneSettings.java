package top.dext.bvc.api.server;

import java.util.Objects;

/**
 * Voice rules of a zone (the sub-server default, or a room). Immutable.
 */
public final class ZoneSettings {

    private final VoiceMode mode;
    private final Activation activation;
    private final double radius;

    public ZoneSettings(VoiceMode mode, Activation activation, double radius) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.activation = Objects.requireNonNull(activation, "activation");
        if (radius < 0) {
            throw new IllegalArgumentException("radius must not be negative");
        }
        this.radius = radius;
    }

    public static ZoneSettings of(VoiceMode mode) {
        return new ZoneSettings(mode, Activation.ANY, 48);
    }

    public VoiceMode mode() {
        return mode;
    }

    public Activation activation() {
        return activation;
    }

    /** Hearing distance in blocks; only used by proximity modes. */
    public double radius() {
        return radius;
    }

    public ZoneSettings withMode(VoiceMode newMode) {
        return new ZoneSettings(newMode, activation, radius);
    }

    public ZoneSettings withActivation(Activation newActivation) {
        return new ZoneSettings(mode, newActivation, radius);
    }

    public ZoneSettings withRadius(double newRadius) {
        return new ZoneSettings(mode, activation, newRadius);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ZoneSettings)) {
            return false;
        }
        ZoneSettings other = (ZoneSettings) o;
        return mode == other.mode && activation == other.activation && Double.compare(radius, other.radius) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, activation, radius);
    }

    @Override
    public String toString() {
        return "ZoneSettings{" + mode + ", " + activation + ", radius=" + radius + "}";
    }
}
