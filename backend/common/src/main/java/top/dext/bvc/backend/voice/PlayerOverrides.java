package top.dext.bvc.backend.voice;

/**
 * Per-player settings made through the API (minigames). Immutable; {@code null} fields mean
 * "not overridden".
 */
public final class PlayerOverrides {

    public static final PlayerOverrides NONE = new PlayerOverrides(null, null, 0, null, null, null);

    public final String room;
    public final String group;
    public final int priority;
    public final Boolean canSpeak;
    public final Boolean canHear;
    public final Boolean canBroadcast;

    public PlayerOverrides(String room, String group, int priority, Boolean canSpeak, Boolean canHear, Boolean canBroadcast) {
        this.room = room;
        this.group = group;
        this.priority = priority;
        this.canSpeak = canSpeak;
        this.canHear = canHear;
        this.canBroadcast = canBroadcast;
    }

    public PlayerOverrides withRoom(String value) {
        return new PlayerOverrides(value, group, priority, canSpeak, canHear, canBroadcast);
    }

    public PlayerOverrides withGroup(String value) {
        return new PlayerOverrides(room, value, priority, canSpeak, canHear, canBroadcast);
    }

    public PlayerOverrides withPriority(int value) {
        return new PlayerOverrides(room, group, value, canSpeak, canHear, canBroadcast);
    }

    public PlayerOverrides withCanSpeak(Boolean value) {
        return new PlayerOverrides(room, group, priority, value, canHear, canBroadcast);
    }

    public PlayerOverrides withCanHear(Boolean value) {
        return new PlayerOverrides(room, group, priority, canSpeak, value, canBroadcast);
    }

    public PlayerOverrides withCanBroadcast(Boolean value) {
        return new PlayerOverrides(room, group, priority, canSpeak, canHear, value);
    }
}
