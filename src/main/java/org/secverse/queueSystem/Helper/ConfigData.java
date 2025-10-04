package org.secverse.queueSystem.Helper;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Objects;


public final class ConfigData {

    private final List<String> targetServers;
    private final String limboServer;

    // Weights for tiers
    private final double weightPremium;
    private final double weightVip;
    private final double weightDefault;
    private final double weightSoftban;

    // Softban
    private final Duration softbanMinWait;

    // Dequeue settings
    private final Duration dequeueInterval;
    private final int dequeueMaxBatch;
    private final boolean dequeueAdaptive;

    // Reconnect and ETA
    private final Duration reconnectGrace;
    private final Duration etaWindow;
    private final double etaMinConfidence;

    // Misc toggles
    private final boolean checkUpdate;
    private final boolean enableTelemetry;
    private final boolean enableLuckPerms;


    // Optional DB settings for LP or other lookups
    private final String dbHost;
    private final int dbPort;
    private final String dbName;
    private final String dbUser;
    private final String dbPass;
    private final boolean dbisSSL;

    /**
     * Constructs an immutable configuration object.
     */
    public ConfigData(
            List<String> targetServers,
            String limboServer,
            double weightPremium,
            double weightVip,
            double weightDefault,
            double weightSoftban,
            Duration softbanMinWait,
            Duration dequeueInterval,
            int dequeueMaxBatch,
            boolean dequeueAdaptive,
            Duration reconnectGrace,
            Duration etaWindow,
            double etaMinConfidence,
            boolean checkUpdate,
            boolean enableTelemetry,
            boolean enableLuckPerms,
            String dbHost,
            int dbPort,
            boolean dbisSSL,
            String dbName,
            String dbUser,
            String dbPass
    ) {
        this.targetServers = List.copyOf(Objects.requireNonNull(targetServers, "targetServers"));
        if (this.targetServers.isEmpty()) {
            throw new IllegalArgumentException("targetServers cannot be empty");
        }
        this.limboServer = Objects.requireNonNull(limboServer, "limboServer");
        this.weightPremium = weightPremium;
        this.weightVip = weightVip;
        this.weightDefault = weightDefault;
        this.weightSoftban = weightSoftban;
        this.softbanMinWait = Objects.requireNonNull(softbanMinWait, "softbanMinWait");
        this.dequeueInterval = Objects.requireNonNull(dequeueInterval, "dequeueInterval");
        if (dequeueMaxBatch <= 0) {
            throw new IllegalArgumentException("dequeueMaxBatch must be > 0");
        }
        this.dequeueMaxBatch = dequeueMaxBatch;
        this.dequeueAdaptive = dequeueAdaptive;
        this.reconnectGrace = Objects.requireNonNull(reconnectGrace, "reconnectGrace");
        this.etaWindow = Objects.requireNonNull(etaWindow, "etaWindow");
        if (etaMinConfidence < 0.0 || etaMinConfidence > 1.0) {
            throw new IllegalArgumentException("etaMinConfidence must be within [0.0, 1.0]");
        }
        this.etaMinConfidence = etaMinConfidence;
        this.checkUpdate = checkUpdate;
        this.enableTelemetry = enableTelemetry;
        this.enableLuckPerms = enableLuckPerms;
        this.dbHost = Objects.requireNonNull(dbHost, "dbHost");
        this.dbPort = dbPort;
        this.dbisSSL = Objects.requireNonNull(dbisSSL, "dbisSSL");
        this.dbName = Objects.requireNonNull(dbName, "dbName");
        this.dbUser = Objects.requireNonNull(dbUser, "dbUser");
        this.dbPass = Objects.requireNonNull(dbPass, "dbPass");

    }

    /** Returns an immutable list of configured target server names. */
    public List<String> getTargetServers() {
        return Collections.unmodifiableList(targetServers);
    }

    /** Returns the limbo server name. */
    public String getLimboServer() {
        return limboServer;
    }

    public Duration getSoftbanMinWait() { return softbanMinWait; }
    public Duration getDequeueInterval() { return dequeueInterval; }
    public int getDequeueMaxBatch() { return dequeueMaxBatch; }

    public boolean isCheckUpdate() { return checkUpdate; }
    public boolean isEnableTelemetry() { return enableTelemetry; }
    public boolean isEnableLuckPerms() { return enableLuckPerms; }

    public String getDbHost() { return dbHost; }
    public int getDbPort() { return dbPort; }
    public String getDbName() { return dbName; }
    public String getDbUser() { return dbUser; }
    public String getDbPass() { return dbPass; }
    public Boolean getDBisSSL() { return dbisSSL; }
}
