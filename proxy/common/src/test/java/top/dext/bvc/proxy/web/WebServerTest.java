package top.dext.bvc.proxy.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import top.dext.bvc.proxy.BvcProxyCore;
import top.dext.bvc.proxy.FakePlatform;
import top.dext.bvc.proxy.FakePlayer;

class WebServerTest {

    @TempDir
    Path data;

    private BvcProxyCore core;
    private FakePlatform platform;
    private String base;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @BeforeEach
    void start() throws Exception {
        Files.writeString(data.resolve("config.yml"), "web:\n  port: 0\n");
        platform = new FakePlatform(data);
        core = new BvcProxyCore(platform);
        core.enable();
        base = core.web().publicUrl();
    }

    @AfterEach
    void stop() {
        core.disable();
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String json, String cookie, String origin) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (cookie != null) {
            request.header("Cookie", cookie);
        }
        if (origin != null) {
            request.header("Origin", origin);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String cookieOf(HttpResponse<?> response) {
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
    }

    @Test
    void oneTimeLinkLogsInOnceAndPermissionsAreEnforced() throws Exception {
        assertEquals(200, get("/", null).statusCode());
        assertTrue(get("/app.js", null).body().contains("BetterVoiceChat"));
        assertEquals(404, get("/../config.yml", null).statusCode());
        assertEquals(401, get("/api/me", null).statusCode());

        // A reviewer without mute rights, through /bvc web in-game.
        FakePlayer staff = platform.add("Staff");
        staff.permissions.add(WebAuth.Permission.REVIEW.node);
        core.onPlayerJoin(staff);
        core.command().execute(staff, new String[] {"web"});
        for (int i = 0; i < 100 && staff.chat.stream().noneMatch(line -> line.contains("/login?token=")); i++) {
            Thread.sleep(20);
        }
        String link = staff.chat.stream().filter(line -> line.contains("/login?token=")).findFirst().orElseThrow();
        String path = link.substring(link.indexOf("/login?token="));

        HttpResponse<String> login = get(path, null);
        assertEquals(302, login.statusCode());
        assertEquals("/", login.headers().firstValue("Location").orElseThrow());
        String cookie = cookieOf(login);
        assertTrue(login.headers().firstValue("Set-Cookie").orElseThrow().contains("HttpOnly"));

        // The token works once only.
        assertEquals("/#expired", get(path, null).headers().firstValue("Location").orElseThrow());

        HttpResponse<String> me = get("/api/me", cookie);
        assertEquals(200, me.statusCode());
        assertTrue(me.body().contains("\"username\":\"staff\""));
        assertTrue(me.body().contains("\"canReview\":true"));
        assertTrue(me.body().contains("\"canMute\":false"));

        assertEquals(200, get("/api/reports", cookie).statusCode());
        assertEquals(403, post("/api/mutes", "{\"player\":\"Staff\"}", cookie, null).statusCode());
        assertEquals(403, get("/api/audit", cookie).statusCode());

        // Cross-site POSTs are refused.
        assertEquals(403, post("/api/logout", "{}", cookie, "https://evil.example").statusCode());

        // Password login after setting one.
        assertEquals(400, post("/api/password", "{\"password\":\"short\"}", cookie, null).statusCode());
        assertEquals(200, post("/api/password", "{\"password\":\"correct horse\"}", cookie, null).statusCode());
        assertEquals(401, post("/api/login", "{\"username\":\"Staff\",\"password\":\"wrong one\"}", null, null).statusCode());
        HttpResponse<String> passwordLogin = post("/api/login", "{\"username\":\"Staff\",\"password\":\"correct horse\"}", null, null);
        assertEquals(200, passwordLogin.statusCode());
        assertEquals(200, get("/api/me", cookieOf(passwordLogin)).statusCode());

        assertEquals(200, post("/api/logout", "{}", cookie, null).statusCode());
        assertEquals(401, get("/api/me", cookie).statusCode());
    }

    @Test
    void consoleCreatesAdminWhoCanMuteAndReadTheAuditLog() throws Exception {
        core.command().execute(platform.console(), new String[] {"web", "Owner", "longpassword"});
        // the console command finishes asynchronously
        for (int i = 0; i < 50 && core.webAuth().user("owner").get().isEmpty(); i++) {
            Thread.sleep(50);
        }
        Thread.sleep(200);
        HttpResponse<String> login = post("/api/login", "{\"username\":\"owner\",\"password\":\"longpassword\"}", null, null);
        assertEquals(200, login.statusCode());
        String cookie = cookieOf(login);

        FakePlayer target = platform.add("Loud");
        core.onPlayerJoin(target);
        for (int i = 0; i < 50 && core.stores().findPlayer("Loud").get().isEmpty(); i++) {
            Thread.sleep(20);
        }
        HttpResponse<String> mute = post("/api/mutes", "{\"player\":\"Loud\",\"scope\":\"*\",\"minutes\":30,\"reason\":\"spam\"}", cookie, null);
        assertEquals(200, mute.statusCode(), mute.body());
        assertTrue(core.mutes().activeMute(target.uuid, "lobby").isPresent());
        assertEquals("web:owner", core.mutes().activeMute(target.uuid, "lobby").get().operator());

        HttpResponse<String> mutes = get("/api/mutes?player=Loud", cookie);
        assertTrue(mutes.body().contains("spam"));
        assertEquals(200, post("/api/mutes/unmute", "{\"player\":\"Loud\",\"scope\":\"*\"}", cookie, null).statusCode());
        assertFalse(core.mutes().activeMute(target.uuid, "lobby").isPresent());

        HttpResponse<String> audit = get("/api/audit", cookie);
        assertEquals(200, audit.statusCode());
        assertTrue(audit.body().contains("\"action\":\"mute\""));
        assertTrue(audit.body().contains("\"action\":\"unmute\""));
        assertTrue(audit.body().contains("create-admin"));

        HttpResponse<String> users = get("/api/users", cookie);
        assertTrue(users.body().contains("\"username\":\"owner\""));
        // An admin cannot delete themself.
        assertEquals(400, post("/api/users/owner/delete", "{}", cookie, null).statusCode());
    }

    @Test
    void permissionEncodingRoundTrips() {
        assertEquals(EnumSet.of(WebAuth.Permission.REVIEW, WebAuth.Permission.ADMIN),
                WebAuth.decode(WebAuth.encode(EnumSet.of(WebAuth.Permission.REVIEW, WebAuth.Permission.ADMIN))));
        assertTrue(WebAuth.decode("JUNK,").isEmpty());
        assertTrue(WebAuth.verify("pw", new WebAuth(null).hash("pw")));
        assertFalse(WebAuth.verify("pw2", new WebAuth(null).hash("pw")));
        assertFalse(UUID.randomUUID().toString().isEmpty());
    }
}
