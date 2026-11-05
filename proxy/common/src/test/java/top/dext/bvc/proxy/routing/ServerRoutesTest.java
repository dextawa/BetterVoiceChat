package top.dext.bvc.proxy.routing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

import top.dext.bvc.protocol.data.Activation;
import top.dext.bvc.protocol.data.VoiceMode;

class ServerRoutesTest {

    @Test
    void staleUpdatesAreIgnored() {
        ServerRoutes routes = new RouteRegistry().getOrCreate("s");
        routes.applyNeighbors(10, 1, new int[] {2, 3});
        routes.applyNeighbors(9, 1, new int[] {4});
        assertArrayEquals(new int[] {2, 3}, routes.route(1).neighbors());

        routes.applyNeighbors(10, 1, new int[] {5});
        assertArrayEquals(new int[] {5}, routes.route(1).neighbors(), "same sequence is a repeat, applied");

        routes.applyMembership(5, 1, 0, 9);
        routes.applyMembership(4, 1, 0, 8);
        assertEquals(9, routes.route(1).groupId());
    }

    @Test
    void groupAndRoomIndexesFollowChanges() {
        ServerRoutes routes = new RouteRegistry().getOrCreate("s");
        routes.applyMembership(1, 1, 0, 7);
        routes.applyMembership(1, 2, 0, 7);
        assertEquals(Set.of(1, 2), routes.groupMembers(7));
        assertEquals(Set.of(1, 2), routes.roomMembers(0));

        routes.applyMembership(2, 1, 3, 8);
        assertEquals(Set.of(2), routes.groupMembers(7));
        assertEquals(Set.of(1), routes.groupMembers(8));
        assertEquals(Set.of(2), routes.roomMembers(0));
        assertEquals(Set.of(1), routes.roomMembers(3));

        routes.remove(2);
        assertTrue(routes.groupMembers(7).isEmpty());
        assertTrue(routes.roomMembers(0).isEmpty());
        assertNull(routes.route(2));
    }

    @Test
    void routeCreatedByFlagsJoinsTheDefaultRoom() {
        ServerRoutes routes = new RouteRegistry().getOrCreate("s");
        routes.applyFlags(1, 5, 300, 3);
        assertEquals(255, routes.route(5).priority(), "priority clamps to 255");
        assertEquals(Set.of(5), routes.roomMembers(0));

        routes.applyMembership(2, 5, 4, 0);
        assertFalse(routes.roomMembers(0).contains(5));
        assertEquals(Set.of(5), routes.roomMembers(4));
    }

    @Test
    void zoneChangeIsReportedOnlyWhenDifferent() {
        ServerRoutes routes = new RouteRegistry().getOrCreate("s");
        assertTrue(routes.applyZone(1, 1, "", VoiceMode.FREE, Activation.ANY, 0f));
        assertFalse(routes.applyZone(2, 1, "", VoiceMode.FREE, Activation.ANY, 0f));
        assertTrue(routes.applyZone(3, 1, "", VoiceMode.FREE, Activation.PTT_ONLY, 0f));
        assertFalse(routes.applyZone(1, 1, "x", VoiceMode.TEAM, Activation.ANY, 0f), "stale");
    }

    @Test
    void newBackendInstanceForgetsOldStateSameInstanceKeepsIt() {
        ServerRoutes routes = new RouteRegistry().getOrCreate("s");
        assertTrue(routes.onBackendHello("1.0", "Paper", 1L));
        routes.applyMembership(100, 1, 0, 7);

        assertFalse(routes.onBackendHello("1.0", "Paper", 1L), "same instance greeting again keeps tables");
        assertEquals(7, routes.route(1).groupId());

        assertTrue(routes.onBackendHello("1.0", "Paper", 2L), "restarted backend");
        assertNull(routes.route(1));
        assertTrue(routes.groupMembers(7).isEmpty());
        routes.applyMembership(1, 1, 0, 7);
        assertEquals(7, routes.route(1).groupId(), "a restarted backend may start with low sequences");
    }
}
