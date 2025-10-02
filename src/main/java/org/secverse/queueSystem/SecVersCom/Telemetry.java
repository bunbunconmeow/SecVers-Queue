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

public final class Telemetry {

    private static final String DEFAULT_ENDPOINT_URL = "https://api.secvers.org/v1/telemetry/VelocityQueue";
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


    public UUID getHwid() {
        return hwid;
    }

    public String getServerName() {
        String ver = safeString(String.valueOf(proxy.getVersion()));
        if (ver.isEmpty()) {
            return "Velocity";
        }
        return "Velocity " + ver;
    }

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

    private void ensureDirectory(File dir) {
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (!created) {
                logger.warning("[Telemetry] Could not create data directory: " + dir.getAbsolutePath());
            }
        }
    }

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

    @SuppressWarnings("unchecked")
    private static String valueToJson(Object val) {
        if (val == null) return "null";
        if (val instanceof Number || val instanceof Boolean) return val.toString();
        if (val instanceof Map) return toJson((Map<String, Object>) val);
        return "\"" + escapeJson(val.toString()) + "\"";
    }


    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }


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

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static String safeString(String s) {
        return s == null ? "" : s;
    }
}
