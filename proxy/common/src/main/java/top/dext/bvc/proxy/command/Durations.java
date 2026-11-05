package top.dext.bvc.proxy.command;

import java.util.Locale;

/**
 * Parses and formats durations like {@code 30m}, {@code 2h}, {@code 7d}, {@code 1d12h}.
 */
public final class Durations {

    /** Returned by {@link #parse} for permanent. */
    public static final long PERMANENT = 0;
    /** Returned by {@link #parse} when the text is not a duration. */
    public static final long INVALID = -1;

    private Durations() {
    }

    public static long parse(String text) {
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (value.equals("perm") || value.equals("permanent") || value.equals("forever") || value.equals("永久")) {
            return PERMANENT;
        }
        if (value.isEmpty()) {
            return INVALID;
        }
        long total = 0;
        long number = -1;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                number = (number < 0 ? 0 : number * 10) + (c - '0');
                if (number > 1_000_000) {
                    return INVALID;
                }
                continue;
            }
            if (number < 0) {
                return INVALID;
            }
            long unit = switch (c) {
                case 's' -> 1_000L;
                case 'm' -> 60_000L;
                case 'h' -> 3_600_000L;
                case 'd' -> 86_400_000L;
                case 'w' -> 7 * 86_400_000L;
                case 'y' -> 365 * 86_400_000L;
                default -> -1L;
            };
            if (unit < 0) {
                return INVALID;
            }
            total += number * unit;
            number = -1;
        }
        if (number >= 0) {
            return INVALID; // trailing number without unit
        }
        return total > 0 ? total : INVALID;
    }

    /** Human readable, Chinese units: {@code 1天2小时}. */
    public static String format(long millis) {
        if (millis <= 0) {
            return "0秒";
        }
        long seconds = millis / 1000;
        long days = seconds / 86_400;
        long hours = seconds % 86_400 / 3_600;
        long minutes = seconds % 3_600 / 60;
        long secs = seconds % 60;
        StringBuilder out = new StringBuilder();
        if (days > 0) {
            out.append(days).append("天");
        }
        if (hours > 0) {
            out.append(hours).append("小时");
        }
        if (minutes > 0 && days == 0) {
            out.append(minutes).append("分钟");
        }
        if (secs > 0 && days == 0 && hours == 0) {
            out.append(secs).append("秒");
        }
        return out.length() == 0 ? "1秒" : out.toString();
    }
}
