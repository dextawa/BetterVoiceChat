package top.dext.bvc.proxy.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import top.dext.bvc.api.Mute;
import top.dext.bvc.api.MuteScope;
import top.dext.bvc.api.MuteService;
import top.dext.bvc.proxy.config.BvcConfig;
import top.dext.bvc.proxy.platform.BvcLogger;
import top.dext.bvc.proxy.recording.Clips;
import top.dext.bvc.proxy.recording.Recorder;
import top.dext.bvc.proxy.recording.RecordingIndex;
import top.dext.bvc.proxy.recording.SegmentFile;
import top.dext.bvc.proxy.report.ReportManager;
import top.dext.bvc.proxy.report.ReportStore;
import top.dext.bvc.proxy.storage.Stores;

/**
 * The web panel: static files from {@code /bvc-web/} in the jar plus a small JSON API, served
 * by the JDK's built-in HTTP server. Meant to sit behind a reverse proxy that adds HTTPS.
 */
public final class WebServer {

    private static final String COOKIE = "bvc_session";
    private static final int MAX_BODY = 64 * 1024;
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "svg", "image/svg+xml",
            "ico", "image/x-icon");

    /** Reports already decoded, so repeated playback requests do not re-read the file. */
    private record CachedClips(long id, List<Clips.Clip> clips) {
    }

    private final WebAuth auth;
    private final ReportManager reports;
    private final Recorder recorder;
    private final MuteService mutes;
    private final Stores stores;
    private final Supplier<BvcConfig> config;
    private final BvcLogger logger;
    private final Gson gson = new Gson();
    private HttpServer server;
    private ExecutorService executor;
    private volatile CachedClips cached;

    public WebServer(WebAuth auth, ReportManager reports, Recorder recorder, MuteService mutes, Stores stores,
                     Supplier<BvcConfig> config, BvcLogger logger) {
        this.auth = auth;
        this.reports = reports;
        this.recorder = recorder;
        this.mutes = mutes;
        this.stores = stores;
        this.config = config;
        this.logger = logger;
    }

    public void start() throws IOException {
        BvcConfig.Web web = config.get().web();
        server = HttpServer.create(new InetSocketAddress(web.bind(), web.port()), 64);
        executor = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "BVC-Web");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        logger.info("Web panel listening on " + web.bind() + ":" + server.getAddress().getPort());
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            executor.shutdownNow();
            server = null;
        }
    }

    /** Base URL for login links. */
    public String publicUrl() {
        BvcConfig.Web web = config.get().web();
        if (!web.publicUrl().isEmpty()) {
            return web.publicUrl();
        }
        String host = web.bind().equals("0.0.0.0") ? "127.0.0.1" : web.bind();
        return "http://" + host + ":" + (server == null ? web.port() : port());
    }

    // ------------------------------------------------------------------ dispatch

    private void handle(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                api(exchange, path.substring(5));
            } else if (path.equals("/login")) {
                linkLogin(exchange);
            } else {
                staticFile(exchange, path);
            }
        } catch (HttpError e) {
            sendJson(exchange, e.status, Map.of("error", e.getMessage()));
        } catch (Throwable error) {
            Throwable cause = error instanceof java.util.concurrent.ExecutionException && error.getCause() != null ? error.getCause() : error;
            if (cause instanceof HttpError e) {
                sendJson(exchange, e.status, Map.of("error", e.getMessage()));
            } else {
                logger.error("Web panel request failed: " + exchange.getRequestURI(), cause);
                sendJson(exchange, 500, Map.of("error", "internal error"));
            }
        } finally {
            exchange.close();
        }
    }

    private static final class HttpError extends RuntimeException {
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private void api(HttpExchange exchange, String route) throws Exception {
        String method = exchange.getRequestMethod();
        long now = System.currentTimeMillis();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            // Browsers always send Origin on cross-site POSTs: refuse those (CSRF).
            String origin = exchange.getRequestHeaders().getFirst("Origin");
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (origin != null && host != null && !origin.endsWith("://" + host)) {
                throw new HttpError(403, "cross-origin request");
            }
        }
        if (route.equals("login") && method.equals("POST")) {
            JsonObject body = body(exchange);
            Optional<String> session = auth.passwordLogin(string(body, "username"), string(body, "password"), now, sessionMillis()).get();
            if (session.isEmpty()) {
                throw new HttpError(401, "wrong username or password");
            }
            setCookie(exchange, session.get());
            auth.audit(WebAuth.normalize(string(body, "username")), "login", "password", now);
            sendJson(exchange, 200, Map.of("ok", true));
            return;
        }

        WebAuth.User user = currentUser(exchange, now);
        String[] parts = route.split("/");
        switch (parts[0]) {
            case "me" -> {
                if (method.equals("GET")) {
                    sendJson(exchange, 200, userJson(user));
                } else {
                    throw new HttpError(405, "method not allowed");
                }
            }
            case "logout" -> {
                auth.logout(cookie(exchange));
                sendJson(exchange, 200, Map.of("ok", true));
            }
            case "password" -> {
                JsonObject body = body(exchange);
                String password = string(body, "password");
                if (password != null && password.length() < 8) {
                    throw new HttpError(400, "password must be at least 8 characters");
                }
                auth.setPassword(user.username(), password == null || password.isEmpty() ? null : password).get();
                auth.audit(user.username(), "password", password == null || password.isEmpty() ? "removed" : "set", now);
                sendJson(exchange, 200, Map.of("ok", true));
            }
            case "reports" -> reports(exchange, user, parts, now);
            case "recordings" -> recordings(exchange, user, parts, now);
            case "mutes" -> mutesApi(exchange, user, parts, now);
            case "users" -> usersApi(exchange, user, parts, now);
            case "audit" -> {
                require(user, WebAuth.Permission.ADMIN);
                Map<String, String> query = query(exchange);
                List<Map<String, Object>> out = new ArrayList<>();
                for (WebAuth.AuditEntry entry : auth.auditLog(clampInt(query.get("limit"), 100, 1, 500),
                        clampInt(query.get("offset"), 0, 0, Integer.MAX_VALUE)).get()) {
                    out.add(Map.of("at", entry.at(), "user", entry.username(), "action", entry.action(), "detail", entry.detail()));
                }
                sendJson(exchange, 200, out);
            }
            default -> throw new HttpError(404, "not found");
        }
    }

    // ------------------------------------------------------------------ reports

    private void reports(HttpExchange exchange, WebAuth.User user, String[] parts, long now) throws Exception {
        require(user, WebAuth.Permission.REVIEW);
        ReportStore store = reports.store();
        if (parts.length == 1) {
            Map<String, String> query = query(exchange);
            ReportStore.Status status = null;
            if (query.containsKey("status") && !query.get("status").isEmpty()) {
                status = parseEnum(ReportStore.Status.class, query.get("status"));
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (ReportStore.Report report : store.list(status, clampInt(query.get("limit"), 50, 1, 200),
                    clampInt(query.get("offset"), 0, 0, Integer.MAX_VALUE)).get()) {
                out.add(reportJson(report));
            }
            sendJson(exchange, 200, out);
            return;
        }
        long id = parseLong(parts[1]);
        ReportStore.Report report = store.get(id).get();
        if (report == null) {
            throw new HttpError(404, "no such report");
        }
        if (parts.length == 2) {
            Map<String, Object> out = reportJson(report);
            List<Map<String, Object>> clips = new ArrayList<>();
            List<Clips.Clip> list = clipsOf(id);
            for (int i = 0; i < list.size(); i++) {
                Clips.Clip clip = list.get(i);
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("index", i);
                json.put("speaker", clip.speaker().toString());
                json.put("name", clip.name());
                json.put("from", clip.from());
                json.put("to", clip.to());
                json.put("heard", clip.heard());
                json.put("role", clip.speaker().equals(report.target()) ? "target"
                        : clip.speaker().equals(report.reporter()) ? "reporter" : "other");
                clips.add(json);
            }
            out.put("clips", clips);
            sendJson(exchange, 200, out);
            auth.audit(user.username(), "view-report", "#" + id, now);
            return;
        }
        if (parts.length == 4 && parts[2].equals("clip")) {
            List<Clips.Clip> list = clipsOf(id);
            int index = (int) parseLong(parts[3]);
            if (index < 0 || index >= list.size()) {
                throw new HttpError(404, "no such clip");
            }
            sendBytes(exchange, 200, "audio/ogg", Clips.toOgg(list.get(index).frames(), (int) (id * 1000 + index)));
            return;
        }
        if (parts.length == 3 && parts[2].equals("status") && exchange.getRequestMethod().equals("POST")) {
            JsonObject body = body(exchange);
            ReportStore.Status status = parseEnum(ReportStore.Status.class, string(body, "status"));
            if (status == ReportStore.Status.COLLECTING) {
                throw new HttpError(400, "bad status");
            }
            if (report.status() == ReportStore.Status.COLLECTING) {
                throw new HttpError(409, "evidence is still being collected");
            }
            String note = string(body, "note");
            boolean reopen = status == ReportStore.Status.OPEN;
            store.setStatus(id, status, reopen ? null : user.username(), now, note).get();
            auth.audit(user.username(), "report-" + status.name().toLowerCase(Locale.ROOT), "#" + id
                    + (note == null || note.isEmpty() ? "" : " " + note), now);
            sendJson(exchange, 200, reportJson(store.get(id).get()));
            return;
        }
        throw new HttpError(404, "not found");
    }

    private List<Clips.Clip> clipsOf(long id) throws IOException {
        CachedClips current = cached;
        if (current != null && current.id() == id) {
            return current.clips();
        }
        List<Clips.Clip> clips = reports.clips(id);
        cached = new CachedClips(id, clips);
        return clips;
    }

    private static Map<String, Object> reportJson(ReportStore.Report report) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", report.id());
        json.put("reporter", report.reporterName());
        json.put("reporterUuid", report.reporter().toString());
        json.put("target", report.targetName());
        json.put("targetUuid", report.target().toString());
        json.put("server", report.server());
        json.put("reason", report.reason());
        json.put("createdAt", report.createdAt());
        json.put("from", report.windowFrom());
        json.put("to", report.windowTo());
        json.put("status", report.status().name());
        json.put("handledBy", report.handledBy());
        json.put("handledAt", report.handledAt());
        json.put("note", report.note());
        return json;
    }

    // ------------------------------------------------------------------ recordings

    private void recordings(HttpExchange exchange, WebAuth.User user, String[] parts, long now) throws Exception {
        require(user, WebAuth.Permission.REVIEW);
        if (parts.length == 1) {
            Map<String, String> query = query(exchange);
            UUID speaker = null;
            String player = query.get("player");
            if (player != null && !player.isBlank()) {
                Optional<Stores.KnownPlayer> known = stores.findPlayer(player.trim()).get();
                if (known.isEmpty()) {
                    sendJson(exchange, 200, List.of());
                    return;
                }
                speaker = known.get().uuid();
            }
            String server = query.get("server");
            long to = parseLongOr(query.get("to"), now);
            long from = parseLongOr(query.get("from"), to - 86_400_000L);
            List<Map<String, Object>> out = new ArrayList<>();
            for (RecordingIndex.Utterance u : recorder.index().search(speaker, server == null || server.isBlank() ? null : server.trim(),
                    from, to, clampInt(query.get("limit"), 100, 1, 500)).get()) {
                Map<String, Object> json = new LinkedHashMap<>();
                json.put("id", u.id());
                json.put("server", u.server());
                json.put("speaker", u.speaker().toString());
                json.put("name", u.speakerName());
                json.put("from", u.startedAt());
                json.put("to", u.endedAt());
                json.put("frames", u.frames());
                out.add(json);
            }
            auth.audit(user.username(), "search-recordings", "player=" + (player == null ? "" : player) + " server="
                    + (server == null ? "" : server), now);
            sendJson(exchange, 200, out);
            return;
        }
        if (parts.length == 3 && parts[2].equals("audio")) {
            long id = parseLong(parts[1]);
            RecordingIndex.Utterance u = recorder.index().get(id).get();
            if (u == null) {
                throw new HttpError(404, "no such recording");
            }
            List<Clips.TimedFrame> frames = new ArrayList<>();
            try {
                SegmentFile.readRange(recorder.directory().resolve(u.segment()), u.offsetFrom(), u.offsetTo(), u.localId(),
                        entry -> frames.add(new Clips.TimedFrame(entry.at(), entry.opus(), 0)));
            } catch (IOException e) {
                throw new HttpError(410, "recording was rotated out");
            }
            auth.audit(user.username(), "play-recording", "#" + id + " " + u.speakerName() + " @" + u.server(), now);
            sendBytes(exchange, 200, "audio/ogg", Clips.toOgg(frames, (int) id));
            return;
        }
        throw new HttpError(404, "not found");
    }

    // ------------------------------------------------------------------ mutes

    private void mutesApi(HttpExchange exchange, WebAuth.User user, String[] parts, long now) throws Exception {
        if (exchange.getRequestMethod().equals("GET")) {
            require(user, WebAuth.Permission.REVIEW);
            String player = query(exchange).get("player");
            if (player == null || player.isBlank()) {
                throw new HttpError(400, "player required");
            }
            Optional<Stores.KnownPlayer> known = stores.findPlayer(player.trim()).get();
            if (known.isEmpty()) {
                throw new HttpError(404, "unknown player");
            }
            List<Map<String, Object>> active = new ArrayList<>();
            for (Mute mute : mutes.activeMutes(known.get().uuid())) {
                active.add(muteJson(mute));
            }
            List<Map<String, Object>> history = new ArrayList<>();
            for (Mute mute : stores.muteHistory(known.get().uuid(), 50).get()) {
                history.add(muteJson(mute));
            }
            sendJson(exchange, 200, Map.of("player", known.get().name(), "uuid", known.get().uuid().toString(),
                    "active", active, "history", history));
            return;
        }
        require(user, WebAuth.Permission.MUTE);
        JsonObject body = body(exchange);
        String player = string(body, "player");
        Optional<Stores.KnownPlayer> known = player == null ? Optional.empty() : stores.findPlayer(player.trim()).get();
        if (known.isEmpty()) {
            throw new HttpError(404, "unknown player");
        }
        String scopeText = string(body, "scope");
        MuteScope scope = scopeText == null || scopeText.isBlank() ? MuteScope.global() : MuteScope.parse(scopeText.trim());
        String operator = "web:" + user.username();
        if (parts.length == 2 && parts[1].equals("unmute")) {
            boolean removed = mutes.unmute(known.get().uuid(), scope, operator);
            auth.audit(user.username(), "unmute", known.get().name() + " " + scope.key(), now);
            sendJson(exchange, 200, Map.of("ok", removed));
            return;
        }
        long minutes = body.has("minutes") && !body.get("minutes").isJsonNull() ? body.get("minutes").getAsLong() : 0;
        if (minutes < 0) {
            throw new HttpError(400, "bad duration");
        }
        String reason = string(body, "reason");
        Mute mute = mutes.mute(known.get().uuid(), known.get().name(), scope, minutes * 60_000L, reason == null ? "" : reason, operator);
        auth.audit(user.username(), "mute", known.get().name() + " " + scope.key() + " " + (minutes == 0 ? "perm" : minutes + "m")
                + (reason == null || reason.isEmpty() ? "" : " " + reason), now);
        sendJson(exchange, 200, muteJson(mute));
    }

    private static Map<String, Object> muteJson(Mute mute) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", mute.id());
        json.put("scope", mute.scope().key());
        json.put("reason", mute.reason());
        json.put("operator", mute.operator());
        json.put("createdAt", mute.createdAt());
        json.put("expiresAt", mute.expiresAt());
        return json;
    }

    // ------------------------------------------------------------------ users

    private void usersApi(HttpExchange exchange, WebAuth.User user, String[] parts, long now) throws Exception {
        require(user, WebAuth.Permission.ADMIN);
        if (exchange.getRequestMethod().equals("GET")) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (WebAuth.User other : auth.users().get()) {
                out.add(userJson(other));
            }
            sendJson(exchange, 200, out);
            return;
        }
        if (parts.length != 3) {
            throw new HttpError(404, "not found");
        }
        String target = parts[1];
        if (parts[2].equals("delete")) {
            if (WebAuth.normalize(target).equals(user.username())) {
                throw new HttpError(400, "cannot delete yourself");
            }
            auth.deleteUser(target).get();
            auth.audit(user.username(), "delete-user", target, now);
            sendJson(exchange, 200, Map.of("ok", true));
        } else if (parts[2].equals("permissions")) {
            Set<WebAuth.Permission> permissions = EnumSet.noneOf(WebAuth.Permission.class);
            JsonObject body = body(exchange);
            if (body.has("permissions") && body.get("permissions").isJsonArray()) {
                body.getAsJsonArray("permissions").forEach(element -> permissions.add(parseEnum(WebAuth.Permission.class, element.getAsString())));
            }
            auth.setPermissions(target, permissions).get();
            auth.audit(user.username(), "set-permissions", target + " " + WebAuth.encode(permissions), now);
            sendJson(exchange, 200, Map.of("ok", true));
        } else {
            throw new HttpError(404, "not found");
        }
    }

    private static Map<String, Object> userJson(WebAuth.User user) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("username", user.username());
        json.put("permissions", user.permissions().stream().map(Enum::name).toList());
        json.put("hasPassword", user.hasPassword());
        json.put("canReview", user.can(WebAuth.Permission.REVIEW));
        json.put("canMute", user.can(WebAuth.Permission.MUTE));
        json.put("isAdmin", user.can(WebAuth.Permission.ADMIN));
        return json;
    }

    // ------------------------------------------------------------------ login link + static

    private void linkLogin(HttpExchange exchange) throws IOException {
        String token = query(exchange).get("token");
        long now = System.currentTimeMillis();
        Optional<String> session = auth.redeem(token, now, sessionMillis());
        if (session.isEmpty()) {
            redirect(exchange, "/#expired");
            return;
        }
        setCookie(exchange, session.get());
        auth.sessionUser(session.get(), now).ifPresent(name -> auth.audit(name, "login", "link", now));
        // Redirect so the token never stays in the address bar or history.
        redirect(exchange, "/");
    }

    private void staticFile(HttpExchange exchange, String path) throws IOException {
        if (path.equals("/")) {
            path = "/index.html";
        }
        if (path.contains("..") || path.contains("\\")) {
            throw new HttpError(404, "not found");
        }
        try (InputStream in = WebServer.class.getResourceAsStream("/bvc-web" + path)) {
            if (in == null) {
                throw new HttpError(404, "not found");
            }
            String extension = path.substring(path.lastIndexOf('.') + 1);
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            sendBytes(exchange, 200, TYPES.getOrDefault(extension, "application/octet-stream"), in.readAllBytes());
        }
    }

    // ------------------------------------------------------------------ helpers

    private WebAuth.User currentUser(HttpExchange exchange, long now) throws Exception {
        Optional<String> name = auth.sessionUser(cookie(exchange), now);
        if (name.isEmpty()) {
            throw new HttpError(401, "not logged in");
        }
        Optional<WebAuth.User> user = auth.user(name.get()).get();
        if (user.isEmpty()) {
            auth.logout(cookie(exchange));
            throw new HttpError(401, "account removed");
        }
        return user.get();
    }

    private static void require(WebAuth.User user, WebAuth.Permission permission) {
        if (!user.can(permission)) {
            throw new HttpError(403, "missing permission " + permission.node);
        }
    }

    private long sessionMillis() {
        return config.get().web().sessionHours() * 3_600_000L;
    }

    private static String cookie(HttpExchange exchange) {
        List<String> headers = exchange.getRequestHeaders().get("Cookie");
        if (headers == null) {
            return null;
        }
        for (String header : headers) {
            for (String part : header.split(";")) {
                String trimmed = part.trim();
                if (trimmed.startsWith(COOKIE + "=")) {
                    return trimmed.substring(COOKIE.length() + 1);
                }
            }
        }
        return null;
    }

    private void setCookie(HttpExchange exchange, String session) {
        boolean secure = config.get().web().publicUrl().startsWith("https://");
        exchange.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + session + "; Path=/; HttpOnly; SameSite=Strict"
                + (secure ? "; Secure" : "") + "; Max-Age=" + sessionMillis() / 1000);
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private static JsonObject body(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) {
            throw new HttpError(413, "request too large");
        }
        if (bytes.length == 0) {
            return new JsonObject();
        }
        try {
            return JsonParser.parseString(WebAuth.utf8(bytes)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new HttpError(400, "bad JSON");
        }
    }

    private static String string(JsonObject body, String key) {
        return body.has(key) && !body.get(key).isJsonNull() ? body.get(key).getAsString() : null;
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> out = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(key, value);
        }
        return out;
    }

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new HttpError(400, "bad number: " + text);
        }
    }

    private static long parseLongOr(String text, long fallback) {
        return text == null || text.isBlank() ? fallback : parseLong(text.trim());
    }

    private static int clampInt(String text, int fallback, int min, int max) {
        return (int) Math.max(min, Math.min(max, parseLongOr(text, fallback)));
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String text) {
        try {
            return Enum.valueOf(type, text == null ? "" : text.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new HttpError(400, "bad value: " + text);
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object body) {
        try {
            sendBytes(exchange, status, "application/json; charset=utf-8", gson.toJson(body).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // client went away
        }
    }

    private static void sendBytes(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
