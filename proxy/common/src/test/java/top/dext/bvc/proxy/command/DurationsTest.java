package top.dext.bvc.proxy.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DurationsTest {

    @Test
    void parsesUnitsAndCombinations() {
        assertEquals(30 * 60_000L, Durations.parse("30m"));
        assertEquals(2 * 3_600_000L, Durations.parse("2h"));
        assertEquals(7 * 86_400_000L, Durations.parse("7d"));
        assertEquals(86_400_000L + 12 * 3_600_000L, Durations.parse("1d12h"));
        assertEquals(Durations.PERMANENT, Durations.parse("perm"));
        assertEquals(Durations.PERMANENT, Durations.parse("永久"));
    }

    @Test
    void rejectsJunk() {
        assertEquals(Durations.INVALID, Durations.parse("abc"));
        assertEquals(Durations.INVALID, Durations.parse("10"));
        assertEquals(Durations.INVALID, Durations.parse("m"));
        assertEquals(Durations.INVALID, Durations.parse("0m"));
        assertEquals(Durations.INVALID, Durations.parse("lobby"));
    }

    @Test
    void formatsInChinese() {
        assertEquals("30分钟", Durations.format(30 * 60_000L));
        assertEquals("1天12小时", Durations.format(86_400_000L + 12 * 3_600_000L));
        assertEquals("1小时5分钟", Durations.format(3_900_000L));
        assertEquals("45秒", Durations.format(45_000L));
    }
}
