package org.secverse.queueSystem.SecVersCom;

import com.velocitypowered.api.proxy.ProxyServer;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Telemetry helper for a Velocity plugin.
 *
 * Responsibilities:
 *  - Generate and persist a unique HWID per plugin installation.
 *  - Provide server name and basic metadata.
 *  - Build telemetry JSON payloads.
 *  - Send telemetry POST requests asynchronously using Velocity scheduler.
 *
 * Design notes:
 *  - Uses java.util.logging.Logger as requested.
 *  - Keeps network I/O off the main thread by scheduling tasks on the Velocity scheduler.
 *  - Endpoint is hardcoded by default and can be overridden via constructor.
 */
public final class Telemetry {

    /** Default hardcoded telemetry endpoint for SecVers. */
    private static final String DEFAULT_ENDPOINT_URL = "https://api.secvers.org/v1/telemetry/SecVersDupeUtils";

    /** File name used to persist the HWID under the plugin data directory. */
    private static final String HWID_FILENAME = "hwid.txt";

    private final Object pluginInstance;
    private final ProxyServer proxy;
    private final Logger logger;
    private final File dataDirectory;
    private final UUID hwid;
    private final boolean telemetryEnabled;

    /**
     * Create a new telemetry helper.
     *
     * @param pluginInstance plugin main instance, required by Velocity scheduler
     * @param proxy Velocity ProxyServer instance
     * @param logger java.util.logging.Logger for log output
     * @param dataDirectory plugin data directory used to persist the HWID
     * @param telemetryEnabled flag to enable or disable telemetry
     */
    public Telemetry(Object pluginInstance,
                             ProxyServer proxy,
                             Logger logger,
                             File dataDirectory,
                             boolean telemetryEnabled) {
        this.pluginInstance = Objects.requireNonNull(pluginInstance, "pluginInstance");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        ensureDirectory(this.dataDirectory);

        this.telemetryEnabled = telemetryEnabled;
        this.hwid = loadOrCreateHwid();
    }


    /**
     * Get the persisted installation HWID.
     *
     * @return UUID used as HWID
     */
    public UUID getHwid() {
        return hwid;
    }

    /**
     * Return a human readable server name. For Velocity this returns a composite of name and version.
     *
     * @return server name string
     */
    public String getServerName() {
        String ver = safeString(String.valueOf(proxy.getVersion()));
        if (ver.isEmpty()) {
            return "Velocity";
        }
        return "Velocity " + ver;
    }

    /**
     * Build a simple telemetry payload. Extend the map for custom fields as needed.
     *
     * @param pluginName plugin display name
     * @param pluginVersion plugin version
     * @param additional optional additional fields to be merged
     * @return JSON string payload
     */
    public String buildPayload(String pluginName, String pluginVersion, Map<String, Object> additional) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("hwid", hwid.toString());
        payload.put("serverName", getServerName());
        payload.put("pluginName", safeString(pluginName));
        payload.put("pluginVersion", safeString(pluginVersion));
        payload.put("timestamp", Instant.now().toString());

        if (additional != null && !additional.isEmpty()) {
            payload.putAll(additional);
        }
        return toJson(payload);
    }

    /**
     * Send telemetry asynchronously using the Velocity scheduler.
     * If telemetry is disabled or endpoint is blank, nothing is sent.
     *
     * @param pluginName plugin display name
     * @param pluginVersion plugin version
     * @param additional optional additional fields
     */
    public void sendTelemetryAsync(String pluginName, String pluginVersion, Map<String, Object> additional) {
        if (!telemetryEnabled) {
            logger.info("[Telemetry] Disabled in configuration. Skipping send.");
            return;
        }


        final String payload = buildPayload(pluginName, pluginVersion, additional);

        proxy.getScheduler()
                .buildTask(pluginInstance, () -> {
                    try {
                        sendPost(DEFAULT_ENDPOINT_URL, payload);
                    } catch (Exception e) {
                        logger.warning("[Telemetry] Failed to send: " + e.getMessage());
                    }
                })
                .schedule();
    }

    /**
     * Optionally schedule periodic telemetry pings at a fixed interval.
     * First send happens after the initial delay.
     *
     * @param pluginName plugin display name
     * @param pluginVersion plugin version
     * @param additional optional additional fields
     * @param intervalSeconds interval in seconds between sends, must be greater than zero
     * @param initialDelaySeconds initial delay in seconds before the first send
     */
    public void startPeriodicTelemetry(String pluginName,
                                       String pluginVersion,
                                       Map<String, Object> additional,
                                       long intervalSeconds,
                                       long initialDelaySeconds) {
        if (!telemetryEnabled) {
            logger.info("[Telemetry] Disabled in configuration. Periodic sending not scheduled.");
            return;
        }
        if (intervalSeconds <= 0) {
            logger.warning("[Telemetry] Interval must be greater than zero. Periodic sending not scheduled.");
            return;
        }

        proxy.getScheduler()
                .buildTask(pluginInstance, () -> sendTelemetryAsync(pluginName, pluginVersion, additional))
                .delay(initialDelaySeconds, TimeUnit.SECONDS)
                .repeat(intervalSeconds, TimeUnit.SECONDS)
                .schedule();

        logger.info("[Telemetry] Periodic telemetry scheduled every " + intervalSeconds + " seconds.");
    }

    /**
     * Send a JSON payload to an HTTP endpoint using POST.
     *
     * @param url endpoint URL
     * @param jsonPayload JSON payload to send
     * @throws IOException on network errors
     */
    private void sendPost(String url, String jsonPayload) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            byte[] out = jsonPayload.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(out.length);
            conn.connect();

            try (OutputStream os = new BufferedOutputStream(conn.getOutputStream())) {
                os.write(out);
            }

            int status = conn.getResponseCode();
            if (status < 200 || status >= 300) {
                String err = readStream(conn.getErrorStream());
                logger.warning("[Telemetry] HTTP " + status + " from server. Body: " + err);
            } else {
                // Debug level is not available on plain JUL by default, use info for visibility or ignore
                logger.info("[Telemetry] Telemetry sent successfully.");
                // Optionally drain response for completeness
                drain(conn.getInputStream());
            }
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Load an existing HWID from hwid.txt or create a new UUID and persist it.
     *
     * @return UUID used as HWID
     */
    private UUID loadOrCreateHwid() {
        File file = new File(dataDirectory, HWID_FILENAME);
        if (file.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String line = br.readLine();
                if (line != null && !line.trim().isEmpty()) {
                    try {
                        return UUID.fromString(line.trim());
                    } catch (IllegalArgumentException ignore) {
                        // fall through to regenerate
                    }
                }
            } catch (IOException ignored) {
                // fall through to regenerate
            }
        }

        UUID newId = UUID.randomUUID();
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(file))) {
            bw.write(newId.toString());
        } catch (IOException e) {
            logger.warning("[Telemetry] Failed to persist HWID: " + e.getMessage());
        }
        return newId;
    }

    /**
     * Ensure a directory exists. Attempt to create it if missing.
     *
     * @param dir directory file
     */
    private void ensureDirectory(File dir) {
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (!created) {
                logger.warning("[Telemetry] Could not create data directory: " + dir.getAbsolutePath());
            }
        }
    }

    /**
     * Convert a map into a minimally escaped JSON string.
     * Supports strings, numbers, booleans, and nested maps.
     *
     * @param map data map
     * @return JSON string
     */
    private static String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escapeJson(e.getKey())).append("\":");
            sb.append(valueToJson(e.getValue()));
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Convert a single value to JSON representation.
     *
     * @param val value to convert
     * @return JSON string
     */
    @SuppressWarnings("unchecked")
    private static String valueToJson(Object val) {
        if (val == null) return "null";
        if (val instanceof Number || val instanceof Boolean) return val.toString();
        if (val instanceof Map) return toJson((Map<String, Object>) val);
        return "\"" + escapeJson(val.toString()) + "\"";
    }

    /**
     * Escape a string for JSON context.
     *
     * @param s input string
     * @return escaped string
     */
    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Read a response or error stream into a string.
     *
     * @param is input stream or null
     * @return string contents or empty string
     */
    private static String readStream(InputStream is) {
        if (is == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                out.append(line);
            }
            return out.toString();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Drain and close a response stream quietly.
     *
     * @param in input stream or null
     */
    private static void drain(InputStream in) {
        if (in == null) return;
        try (BufferedInputStream bis = new BufferedInputStream(in)) {
            byte[] buf = new byte[1024];
            while (bis.read(buf) != -1) {
                // discard
            }
        } catch (IOException ignore) {
            // ignore
        }
    }

    /**
     * Return true if a string is null, empty, or whitespace only.
     *
     * @param s string
     * @return boolean
     */
    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * Return a non-null string, empty if input is null.
     *
     * @param s string
     * @return non-null string
     */
    private static String safeString(String s) {
        return s == null ? "" : s;
    }
}
