package top.dext.bvc.proxy.mute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import top.dext.bvc.api.Mute;
import top.dext.bvc.api.MuteListener;
import top.dext.bvc.api.MuteScope;
import top.dext.bvc.proxy.MutableClock;
import top.dext.bvc.proxy.TestLogger;
import top.dext.bvc.proxy.storage.Database;
import top.dext.bvc.proxy.storage.Stores;

class MuteManagerTest {

    private static final UUID TARGET = UUID.randomUUID();

    @Test
    void mutesSurviveRestartAndUnmutesToo() throws Exception {
        MutableClock clock = new MutableClock(1_000_000L);
        try (Database database = Database.openInMemory("mutes-" + UUID.randomUUID())) {
            MuteManager first = manager(database, clock, new ArrayList<>());
            first.mute(TARGET, "Target", MuteScope.global(), 0, "toxic", "Admin");
            first.mute(TARGET, "Target", MuteScope.server("bedwars"), 60_000, "spam", "Admin");
            first.setServerMuted("Lobby", true, "Admin");
            first.setGlobalMuteEnabled(true, "Admin");

            MuteManager second = manager(database, clock, new ArrayList<>());
            second.load();
            assertEquals(2, second.activeMutes(TARGET).size());
            assertTrue(second.activeMute(TARGET, "anything").get().scope().isGlobal());
            assertTrue(second.isServerMuted("lobby"));
            assertTrue(second.isGlobalMuteEnabled());

            assertTrue(second.unmute(TARGET, MuteScope.global(), "Admin"));
            assertFalse(second.unmute(TARGET, MuteScope.global(), "Admin"));

            MuteManager third = manager(database, clock, new ArrayList<>());
            third.load();
            assertEquals(1, third.activeMutes(TARGET).size());
            assertEquals("bedwars", third.activeMute(TARGET, "BedWars").get().scope().server());
            assertTrue(third.activeMute(TARGET, "lobby").isEmpty());
        }
    }

    @Test
    void sameScopeReplacesAndExpiryNotifies() throws Exception {
        MutableClock clock = new MutableClock(1_000_000L);
        try (Database database = Database.openInMemory("mutes-" + UUID.randomUUID())) {
            List<UUID> changed = new ArrayList<>();
            MuteManager manager = manager(database, clock, changed);
            List<String> events = new ArrayList<>();
            manager.addListener(new MuteListener() {
                @Override
                public void onMute(Mute mute) {
                    events.add("mute " + mute.reason());
                }

                @Override
                public void onUnmute(Mute mute, String operator) {
                    events.add("unmute " + operator);
                }
            });

            manager.mute(TARGET, "Target", MuteScope.global(), 10_000, "first", "Admin");
            manager.mute(TARGET, "Target", MuteScope.global(), 20_000, "second", "Admin");
            assertEquals(1, manager.activeMutes(TARGET).size());
            assertEquals("second", manager.activeMutes(TARGET).get(0).reason());

            clock.advance(25_000);
            changed.clear();
            manager.purgeExpired();
            assertTrue(manager.activeMutes(TARGET).isEmpty());
            assertEquals(List.of(TARGET), changed);
            assertEquals(List.of("mute first", "mute second", "unmute expired"), events);

            // Expired mutes are not reloaded.
            MuteManager reloaded = manager(database, clock, new ArrayList<>());
            reloaded.load();
            assertTrue(reloaded.activeMutes(TARGET).isEmpty());
        }
    }

    private static MuteManager manager(Database database, MutableClock clock, List<UUID> changed) {
        return new MuteManager(new Stores(database), clock, new TestLogger(), changed::add, () -> {
        });
    }
}
