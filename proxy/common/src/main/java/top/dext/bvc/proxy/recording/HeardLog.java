package top.dext.bvc.proxy.recording;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * For each listener, the recent intervals during which they heard each speaker. A report uses
 * it to rebuild what the reporter actually heard. Called for every forwarded frame and
 * listener, so it only extends the last interval or appends a new one.
 */
public final class HeardLog {

    /** Frames closer than this belong to the same interval. */
    static final long MERGE_GAP_MILLIS = 1000;

    public record Interval(UUID speaker, long from, long to) {
    }

    private static final class Mutable {
        final UUID speaker;
        final long from;
        long to;

        Mutable(UUID speaker, long from) {
            this.speaker = speaker;
            this.from = from;
            this.to = from;
        }
    }

    private static final class Listener {
        final Deque<Mutable> intervals = new ArrayDeque<>();
        /** Open interval per speaker, to extend without scanning. */
        final Map<UUID, Mutable> open = new java.util.HashMap<>();
    }

    private final Map<UUID, Listener> listeners = new ConcurrentHashMap<>();
    private volatile long retentionMillis;

    public HeardLog(long retentionMillis) {
        this.retentionMillis = retentionMillis;
    }

    public void setRetention(long retentionMillis) {
        this.retentionMillis = retentionMillis;
    }

    public void heard(UUID listener, UUID speaker, long now) {
        Listener log = listeners.computeIfAbsent(listener, uuid -> new Listener());
        synchronized (log) {
            Mutable current = log.open.get(speaker);
            if (current != null && now - current.to <= MERGE_GAP_MILLIS) {
                current.to = Math.max(current.to, now);
                return;
            }
            Mutable interval = new Mutable(speaker, now);
            log.open.put(speaker, interval);
            log.intervals.addLast(interval);
            prune(log, now);
        }
    }

    /** Intervals of {@code listener} overlapping {@code [from, to]}. */
    public List<Interval> intervals(UUID listener, long from, long to) {
        Listener log = listeners.get(listener);
        List<Interval> out = new ArrayList<>();
        if (log == null) {
            return out;
        }
        synchronized (log) {
            for (Mutable interval : log.intervals) {
                if (interval.from <= to && interval.to >= from) {
                    out.add(new Interval(interval.speaker, interval.from, interval.to));
                }
            }
        }
        return out;
    }

    /** Drops everything older than the retention; also forgets listeners with nothing left. */
    public void purge(long now) {
        for (Iterator<Map.Entry<UUID, Listener>> it = listeners.entrySet().iterator(); it.hasNext(); ) {
            Listener log = it.next().getValue();
            synchronized (log) {
                prune(log, now);
                if (log.intervals.isEmpty()) {
                    it.remove();
                }
            }
        }
    }

    private void prune(Listener log, long now) {
        long cutoff = now - retentionMillis;
        while (!log.intervals.isEmpty() && log.intervals.peekFirst().to < cutoff) {
            Mutable old = log.intervals.removeFirst();
            log.open.remove(old.speaker, old);
        }
    }
}
