package top.dext.bvc.proxy.session;

import java.util.Arrays;

/**
 * Caps how many speakers one listener receives at once. When full, a new speaker only gets
 * in by out-ranking the lowest-priority active one; speakers silent for a moment free their slot.
 */
final class ListenerStreams {

    /** A speaker without frames for this long no longer holds a slot. */
    static final long STALE_MILLIS = 300;

    private int[] speakers = new int[8];
    private int[] priorities = new int[8];
    private long[] lastSeen = new long[8];
    private int count;

    synchronized boolean admit(int speaker, int priority, long now, int cap) {
        for (int i = 0; i < count; i++) {
            if (speakers[i] == speaker) {
                priorities[i] = priority;
                lastSeen[i] = now;
                return true;
            }
        }
        for (int i = 0; i < count; ) {
            if (now - lastSeen[i] > STALE_MILLIS) {
                removeAt(i);
            } else {
                i++;
            }
        }
        if (count < cap) {
            add(speaker, priority, now);
            return true;
        }
        int weakest = 0;
        for (int i = 1; i < count; i++) {
            if (priorities[i] < priorities[weakest]) {
                weakest = i;
            }
        }
        if (priority > priorities[weakest]) {
            speakers[weakest] = speaker;
            priorities[weakest] = priority;
            lastSeen[weakest] = now;
            return true;
        }
        return false;
    }

    synchronized void release(int speaker) {
        for (int i = 0; i < count; i++) {
            if (speakers[i] == speaker) {
                removeAt(i);
                return;
            }
        }
    }

    synchronized boolean isActive(int speaker) {
        for (int i = 0; i < count; i++) {
            if (speakers[i] == speaker) {
                return true;
            }
        }
        return false;
    }

    private void add(int speaker, int priority, long now) {
        if (count == speakers.length) {
            int size = count * 2;
            speakers = Arrays.copyOf(speakers, size);
            priorities = Arrays.copyOf(priorities, size);
            lastSeen = Arrays.copyOf(lastSeen, size);
        }
        speakers[count] = speaker;
        priorities[count] = priority;
        lastSeen[count] = now;
        count++;
    }

    private void removeAt(int index) {
        count--;
        speakers[index] = speakers[count];
        priorities[index] = priorities[count];
        lastSeen[index] = lastSeen[count];
    }
}
