package org.secverse.queueSystem.http;

import com.sun.net.httpserver.*;
import org.secverse.queueSystem.DB.DBManager;

import javax.net.ssl.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Requires "Authorization: Bearer <token>" header for all endpoints.
 *
 * Endpoints:
 *   POST /api/skip/create            -> body: { "ttl": minutes, "len": codeLength, "createdBy": "stripe" } | x-www-form-urlencoded same keys
 *                                      returns: { "code": "...", "ttlSeconds": 1800 }
 *   GET  /api/skip/create?ttl=..&len=..&createdBy=..   (optional if you enable it)
 *
 *   POST /api/skip/insert            -> body: { "code": "...", "ttl": minutes, "createdBy": "staff" }
 *                                      returns: { "ok": true }
 *
 *   GET  /api/skip/verify?code=...   -> returns: { "valid": true|false, "secondsLeft": N? }
 */
public final class SkipHttpsServer {

    private final DBManager db;
    private final AuthTokenManager auth;
    private final boolean allowGetCreate;
    private HttpsServer server;

    public SkipHttpsServer(DBManager db, AuthTokenManager auth, boolean allowGetCreate) {
        this.db = Objects.requireNonNull(db, "db");
        this.auth = Objects.requireNonNull(auth, "auth");
        this.allowGetCreate = allowGetCreate;
    }

    public void start(String bindHost, int port, String keystorePath, String keystorePassword) throws Exception {
        server = HttpsServer.create(new InetSocketAddress(bindHost, port), 0);
        SSLContext ssl = buildSslContext(keystorePath, keystorePassword);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/api/skip/create", wrapAuthed(this::handleCreate));
        server.createContext("/api/skip/insert", wrapAuthed(this::handleInsert));
        server.createContext("/api/skip/verify", wrapAuthed(this::handleVerify));

        server.start();
    }

    public void stop(int delaySeconds) {
        if (server != null) server.stop(delaySeconds);
        server = null;
    }

    /* ==========================================
       Handlers
       ========================================== */

    private Void handleCreate(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod()) && !allowGetCreate) {
            return respond(ex, 405, json(Map.of("error", "method_not_allowed")));
        }

        Map<String, String> kv = readParams(ex);
        int ttlMin = parseInt(kv.get("ttl"), 30, 1, 24 * 60);
        int len = parseInt(kv.get("len"), 16, 8, 64);
        String createdBy = safeString(kv.getOrDefault("createdBy", "api"));

        String code = randomCode(len);
        Instant now = Instant.now();
        Instant exp = now.plus(Duration.ofMinutes(ttlMin));
        InetAddress ip = clientIp(ex);

        boolean ok = db.insertSkipToken(code, now, exp, createdBy, ip);
        if (!ok) return respond(ex, 500, json(Map.of("error", "db_error")));

        long ttlSec = Math.max(0, exp.getEpochSecond() - now.getEpochSecond());
        return respond(ex, 200, json(Map.of("code", code, "ttlSeconds", ttlSec)));
    }

    private Void handleInsert(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            return respond(ex, 405, json(Map.of("error", "method_not_allowed")));
        }
        Map<String, String> kv = readParams(ex);
        String code = kv.get("code");
        if (code == null || code.isBlank()) {
            return respond(ex, 400, json(Map.of("error", "missing_code")));
        }
        int ttlMin = parseInt(kv.get("ttl"), 30, 1, 24 * 60);
        String createdBy = safeString(kv.getOrDefault("createdBy", "api"));
        Instant now = Instant.now();
        Instant exp = now.plus(Duration.ofMinutes(ttlMin));
        InetAddress ip = clientIp(ex);

        boolean ok = db.insertSkipToken(code.trim().toUpperCase(Locale.ROOT), now, exp, createdBy, ip);
        return respond(ex, 200, json(Map.of("ok", ok)));
    }

    private Void handleVerify(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex.getRequestURI().getRawQuery());
        String code = q.get("code");
        if (code == null) return respond(ex, 400, json(Map.of("error", "missing_code")));
        boolean valid = db.isSkipTokenValid(code.trim());
        long secondsLeft = 0;
        if (valid) {
            // @ToDo: Add this DB Manager Lookup
        }
        return respond(ex, 200, json(secondsLeft > 0
                ? Map.of("valid", true, "secondsLeft", secondsLeft)
                : Map.of("valid", valid)));
    }

    /* ==========================================
       Auth wrapper and utilities
       ========================================== */

    private HttpHandler wrapAuthed(ThrowingHandler h) {
        return ex -> {
            try {
                String authz = ex.getRequestHeaders().getFirst("Authorization");
                if (!auth.isAuthorized(authz)) {
                    respond(ex, 401, json(Map.of("error", "unauthorized")));
                    return;
                }
                h.handle(ex);
            } catch (Throwable t) {
                respond(ex, 500, json(Map.of("error", "internal_server_error")));
            } finally {
                ex.close();
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingHandler { void handle(HttpExchange ex) throws Exception; }

    private static SSLContext buildSslContext(String path, String pass) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(path)) {
            ks.load(fis, pass.toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass.toCharArray());
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);
        return ssl;
    }

    private static Map<String, String> readParams(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body != null && !body.isBlank()) {
            if (body.trim().startsWith("{")) {
                Map<String, String> m = new HashMap<>();
                String s = body.trim().replaceAll("[{}\"\\s]", "");
                for (String p : s.split(",")) {
                    String[] kv = p.split(":", 2);
                    if (kv.length == 2) m.put(kv[0], kv[1]);
                }
                return m;
            }
            return query(body);
        }
        return Collections.emptyMap();
    }

    private static InetAddress clientIp(HttpExchange ex) {
        try {
            return InetAddress.getByName(ex.getRemoteAddress().getAddress().getHostAddress());
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isBlank()) return m;
        for (String p : raw.split("&")) {
            int i = p.indexOf('=');
            if (i <= 0) continue;
            String k = java.net.URLDecoder.decode(p.substring(0, i), StandardCharsets.UTF_8);
            String v = java.net.URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8);
            m.put(k, v);
        }
        return m;
    }

    private static String json(Map<String, ?> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, ?> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(e.getKey()).append('"').append(':');
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) sb.append(v.toString());
            else sb.append('"').append(String.valueOf(v).replace("\"", "\\\"")).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private static int parseInt(String s, int def, int min, int max) {
        try {
            int v = Integer.parseInt(s);
            if (v < min) return def;
            if (v > max) return def;
            return v;
        } catch (Exception e) {
            return def;
        }
    }

    private static String randomCode(int len) {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom rng = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(alphabet.charAt(rng.nextInt(alphabet.length())));
        return sb.toString();
    }

    private static String safeString(String s) {
        if (s == null) return "";
        return s.replaceAll("[^a-zA-Z0-9_:\\-\\.]", "").substring(0, Math.min(64, s.length()));
    }

    private static Void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        return null;
    }
}
