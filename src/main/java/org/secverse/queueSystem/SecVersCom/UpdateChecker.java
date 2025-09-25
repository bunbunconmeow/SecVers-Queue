package org.secverse.queueSystem.SecVersCom;

import com.google.common.base.Strings;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import java.util.logging.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Velocity update checker that fetches the latest version from a hardcoded SecVers API endpoint.
 * It supports one-shot asynchronous checks and periodic checks on a fixed interval.
 */
public final class UpdateChecker {

    private static final String ENDPOINT_URL = "https://api.secvers.org/v1/plugin/VelocityQueue";
    private static final String DOWNLOAD_URL = "https://secvers.org/";
    private static final Pattern SIMPLE_JSON_VERSION =
            Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");

    private final Object pluginInstance;
    private final ProxyServer proxy;
    private final Logger logger;
    private final PluginContainer container;
    private final AtomicReference<ScheduledTask> periodicTask = new AtomicReference<>();

    /**
     * Creates a new UpdateChecker for a Velocity plugin.
     *
     * @param pluginInstance the plugin main instance used for scheduling
     * @param proxy          the Velocity ProxyServer
     * @param logger         the plugin logger
     * @param container      the PluginContainer to read the current version from
     */
    public UpdateChecker(Object pluginInstance,
                         ProxyServer proxy,
                         Logger logger,
                         PluginContainer container) {
        this.pluginInstance = Objects.requireNonNull(pluginInstance, "pluginInstance");
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.container = Objects.requireNonNull(container, "container");
    }

    /**
     * Starts a periodic version check and also performs an immediate asynchronous check now.
     * If a previous periodic task exists it will be cancelled and replaced.
     *
     * @param interval how often to check the API
     */


    /**
     * Cancels the periodic check if running.
     */
    public void cancelPeriodicChecks() {
        ScheduledTask current = periodicTask.getAndSet(null);
        if (current != null) {
            current.cancel();
            logger.info("[Update] Periodic update checks cancelled.");
        }
    }

    /**
     * Runs a single asynchronous update check on the Velocity scheduler.
     */
    public void checkNowAsync() {
        proxy.getScheduler()
                .buildTask(pluginInstance, this::safeCheckNow)
                .schedule();
    }

    /**
     * Runs the check and swallows exceptions into a warning log entry.
     * Never call blocking code from the main thread.
     */
    private void safeCheckNow() {
        try {
            doCheckOnce();
        } catch (Exception ex) {
            logger.warning("[Update] Failed to check for updates");
        }
    }

    /**
     * Performs a single blocking check:
     * 1) Fetch remote version from SecVers endpoint
     * 2) Compare against local plugin version
     * 3) Broadcast and log if outdated, otherwise log an up-to-date message
     *
     * @throws Exception if network or parsing fails
     */
    private void doCheckOnce() throws Exception {
        String remoteVersion = fetchRemoteVersion(ENDPOINT_URL);
        if (Strings.isNullOrEmpty(remoteVersion)) {
            logger.warning("[Update] Could not parse remote version from endpoint.");
            return;
        }

        String localVersion = getLocalVersion().orElse("0");
        if (isOutdated(localVersion, remoteVersion)) {
            broadcastUpdate(remoteVersion, localVersion);
        } else {
            logger.info("[Update] Plugin is up-to-date. Local " + localVersion +", Remote " + remoteVersion);
        }
    }

    /**
     * Fetches a JSON payload from the given URL and extracts the "version" field.
     *
     * @param url endpoint URL that returns JSON
     * @return the version string if found, otherwise null
     * @throws Exception for network or HTTP errors
     */
    private String fetchRemoteVersion(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");

        int code = conn.getResponseCode();
        if (code != 200) {
            try {
                String err = readStreamSafely(conn.getErrorStream());
                if (!err.isEmpty()) {
                    logger.warning("[Update] HTTP " + code + " error body: " + err);
                }
            } catch (Exception ignore) {
                // ignore reading error body
            } finally {
                conn.disconnect();
            }
            throw new IllegalStateException("HTTP " + code);
        }

        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String ln;
            while ((ln = br.readLine()) != null) {
                sb.append(ln);
            }

            String json = sb.toString();
            Matcher m = SIMPLE_JSON_VERSION.matcher(json);
            if (m.find()) {
                return m.group(1).trim();
            }
            return null;
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Reads an input stream to a String or returns empty if the stream is null.
     *
     * @param in input stream from a connection
     * @return string contents or empty string
     */
    private static String readStreamSafely(java.io.InputStream in) {
        if (in == null) return "";
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String ln;
            while ((ln = br.readLine()) != null) {
                sb.append(ln);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Returns the current plugin version from the Velocity plugin description.
     *
     * @return optional version value or empty if missing
     */
    private Optional<String> getLocalVersion() {
        return container.getDescription().getVersion();
    }

    /**
     * Compares local and remote versions with a conservative normalization approach.
     * If the normalized strings differ, the plugin is considered outdated.
     *
     * @param local  local plugin version
     * @param remote remote version from API
     * @return true if local is outdated, false if equal after normalization
     */
    public static boolean isOutdated(String local, String remote) {
        return !normalize(local).equals(normalize(remote));
    }

    /**
     * Normalizes a version by:
     * 1) Dropping build metadata and qualifiers after + or -
     * 2) Keeping only digits and dots
     * 3) Returning "0" if nothing remains
     *
     * @param v version string to normalize
     * @return normalized version
     */
    private static String normalize(String v) {
        if (v == null) return "0";
        String core = v.split("[+-]")[0];
        core = core.replaceAll("[^0-9.]", "");
        return core.isEmpty() ? "0" : core;
    }

    /**
     * Broadcasts an update message to all players and the console.
     * Uses Adventure components for Velocity.
     *
     * @param remoteVersion the newest version
     * @param localVersion  the current version
     */
    private void broadcastUpdate(String remoteVersion, String localVersion) {
        String msg = "[SecVers] New update available: " + remoteVersion
                + " (current " + localVersion + "). Download: " + DOWNLOAD_URL;

        // Console
        proxy.getConsoleCommandSource().sendMessage(Component.text(msg));

        // Players
        proxy.getAllPlayers().forEach(p -> p.sendMessage(Component.text("§c§l" + msg)));

        // Log
        logger.warning("[Update] " + msg);
    }
}
