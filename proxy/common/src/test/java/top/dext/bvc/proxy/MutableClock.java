package top.dext.bvc.proxy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock tests can move by hand.
 */
public final class MutableClock extends Clock {

    private volatile long millis;

    public MutableClock(long millis) {
        this.millis = millis;
    }

    public void advance(long delta) {
        millis += delta;
    }

    public void set(long value) {
        millis = value;
    }

    @Override
    public long millis() {
        return millis;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
