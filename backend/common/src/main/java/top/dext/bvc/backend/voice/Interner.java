package top.dext.bvc.backend.voice;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Hands out stable int ids for string keys (rooms, teams). Keys unused during a whole planning
 * pass are forgotten by {@link #sweep()}; ids are never reused, so a key that comes back simply
 * gets a fresh id.
 */
final class Interner {

    private final Map<String, Integer> ids = new HashMap<String, Integer>();
    private final Set<String> used = new HashSet<String>();
    private int next;

    Interner(int firstId) {
        this.next = firstId;
    }

    int id(String key) {
        used.add(key);
        Integer id = ids.get(key);
        if (id == null) {
            id = next++;
            ids.put(key, id);
        }
        return id;
    }

    void sweep() {
        ids.keySet().retainAll(used);
        used.clear();
    }

    int size() {
        return ids.size();
    }
}
