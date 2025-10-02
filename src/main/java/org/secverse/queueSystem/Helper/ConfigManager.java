package org.secverse.queueSystem.Helper;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Logger;

public final class ConfigManager {

    private final Path configPath;
    private final Logger logger;
    private final List<Consumer<ConfigData>> reloadListeners = new ArrayList<>();


    public ConfigManager(Path configPath, Logger logger) {
        this.configPath = Objects.requireNonNull(configPath, "configPath");
        this.logger = Objects.requireNonNull(logger, "logger");
    }


    public ConfigData loadOrCreate() {
        try {
            ensureFileExistsWithDefaults(configPath);
            CommentedConfigurationNode root = loadNode(configPath);
            applyBackfillDefaults(root);
            saveNode(configPath, root);
            ConfigData data = materialize(root);
            logger.info("[ConfigManager] Configuration loaded");
            return data;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load config.yml: " + ex.getMessage(), ex);
        }
    }


    public ConfigData reload() {
        ConfigData data = loadOrCreate();
        for (Consumer<ConfigData> listener : reloadListeners) {
            safeInvoke(listener, data);
        }
        return data;
    }


    public void addReloadListener(Consumer<ConfigData> listener) {
        if (listener != null) {
            reloadListeners.add(listener);
        }
    }

    private void safeInvoke(Consumer<ConfigData> listener, ConfigData data) {
        try {
            listener.accept(data);
        } catch (Throwable t) {
            logger.warning("[ConfigManager] Reload listener threw: " + t.getMessage());
        }
    }

    private static CommentedConfigurationNode loadNode(Path path) throws IOException {
        YamlConfigurationLoader loader = YamlConfigurationLoader.builder().path(path).build();
        return loader.load();
    }

    private static void saveNode(Path path, CommentedConfigurationNode node) throws IOException {
        YamlConfigurationLoader loader = YamlConfigurationLoader.builder().path(path).build();
        loader.save(node);
    }

    private static void ensureFileExistsWithDefaults(Path path) throws IOException {
        if (Files.exists(path)) return;
        Files.createDirectories(path.getParent());
        YamlConfigurationLoader loader = YamlConfigurationLoader.builder().path(path).build();
        CommentedConfigurationNode root = loader.createNode();

        root.node("targetServers").set(List.of("survival-1"));
        root.node("limboServer").set("queue");

        CommentedConfigurationNode weights = root.node("weights");
        weights.node("premium").set(5.0);
        weights.node("vip").set(3.0);
        weights.node("default").set(1.0);
        weights.node("softban").set(0.5);

        CommentedConfigurationNode softban = root.node("softban");
        softban.node("minWaitMinutes").set(5);

        CommentedConfigurationNode dequeue = root.node("dequeue");
        dequeue.node("intervalSeconds").set(5);
        dequeue.node("maxBatch").set(3);
        dequeue.node("adaptive").set(true);

        root.node("reconnectGraceSeconds").set(45);

        CommentedConfigurationNode eta = root.node("eta");
        eta.node("windowMinutes").set(3);
        eta.node("minConfidence").set(0.6);

        root.node("checkUpdate").set(true);
        root.node("enableTelemetry").set(true);
        root.node("enableLuckPerms").set(false);

        CommentedConfigurationNode db = root.node("database");
        db.node("host").set("127.0.0.1");
        db.node("port").set(3306);
        db.node("name").set("luckperms");
        db.node("user").set("root");
        db.node("pass").set("");
        db.node("useSSL").set(false);

        loader.save(root);
    }

    private static void applyBackfillDefaults(CommentedConfigurationNode root) throws SerializationException {
        if (root.node("targetServers").virtual()) root.node("targetServers").set(List.of("survival-1"));
        if (root.node("limboServer").virtual()) root.node("limboServer").set("queue");

        CommentedConfigurationNode weights = root.node("weights");
        if (weights.node("premium").virtual()) weights.node("premium").set(5.0);
        if (weights.node("vip").virtual()) weights.node("vip").set(3.0);
        if (weights.node("default").virtual()) weights.node("default").set(1.0);
        if (weights.node("softban").virtual()) weights.node("softban").set(0.5);

        CommentedConfigurationNode softban = root.node("softban");
        if (softban.node("minWaitMinutes").virtual()) softban.node("minWaitMinutes").set(5);

        CommentedConfigurationNode dequeue = root.node("dequeue");
        if (dequeue.node("intervalSeconds").virtual()) dequeue.node("intervalSeconds").set(5);
        if (dequeue.node("maxBatch").virtual()) dequeue.node("maxBatch").set(3);
        if (dequeue.node("adaptive").virtual()) dequeue.node("adaptive").set(true);

        if (root.node("reconnectGraceSeconds").virtual()) root.node("reconnectGraceSeconds").set(45);

        CommentedConfigurationNode eta = root.node("eta");
        if (eta.node("windowMinutes").virtual()) eta.node("windowMinutes").set(3);
        if (eta.node("minConfidence").virtual()) eta.node("minConfidence").set(0.6);

        if (root.node("checkUpdate").virtual()) root.node("checkUpdate").set(true);
        if (root.node("enableTelemetry").virtual()) root.node("enableTelemetry").set(true);
        if (root.node("enableLuckPerms").virtual()) root.node("enableLuckPerms").set(false);

        CommentedConfigurationNode db = root.node("database");
        if (db.node("host").virtual()) db.node("host").set("127.0.0.1");
        if (db.node("port").virtual()) db.node("port").set(3306);
        if (db.node("name").virtual()) db.node("name").set("luckperms");
        if (db.node("user").virtual()) db.node("user").set("root");
        if (db.node("pass").virtual()) db.node("pass").set("");
        if(db.node("useSSL").virtual()) db.node("useSSL").set(false);
    }

    private static ConfigData materialize(CommentedConfigurationNode root) throws SerializationException {
        List<String> targets = new ArrayList<>(root.node("targetServers").getList(String.class, List.of("survival-1")));
        String limbo = root.node("limboServer").getString("queue");

        double wPremium = root.node("weights").node("premium").getDouble(5.0);
        double wVip = root.node("weights").node("vip").getDouble(3.0);
        double wDefault = root.node("weights").node("default").getDouble(1.0);
        double wSoftban = root.node("weights").node("softban").getDouble(0.5);

        int softbanMin = root.node("softban").node("minWaitMinutes").getInt(5);
        int intervalSec = root.node("dequeue").node("intervalSeconds").getInt(5);
        int maxBatch = root.node("dequeue").node("maxBatch").getInt(3);
        boolean adaptive = root.node("dequeue").node("adaptive").getBoolean(true);

        int reconnectGrace = root.node("reconnectGraceSeconds").getInt(45);
        int etaWindowMin = root.node("eta").node("windowMinutes").getInt(3);
        double etaMinConf = root.node("eta").node("minConfidence").getDouble(0.6);

        boolean checkUpdate = root.node("checkUpdate").getBoolean(true);
        boolean telemetry = root.node("enableTelemetry").getBoolean(true);
        boolean enableLP = root.node("enableLuckPerms").getBoolean(false);

        String dbHost = root.node("database").node("host").getString("127.0.0.1");
        int dbPort = root.node("database").node("port").getInt(3306);
        String dbName = root.node("database").node("name").getString("luckperms");
        String dbUser = root.node("database").node("user").getString("root");
        String dbPass = root.node("database").node("pass").getString("");
        boolean dbisSSL = root.node("database").node("useSSL").getBoolean(false);

        return new ConfigData(
                targets,
                limbo,
                wPremium,
                wVip,
                wDefault,
                wSoftban,
                Duration.ofMinutes(softbanMin),
                Duration.ofSeconds(intervalSec),
                maxBatch,
                adaptive,
                Duration.ofSeconds(reconnectGrace),
                Duration.ofMinutes(etaWindowMin),
                etaMinConf,
                checkUpdate,
                telemetry,
                enableLP,
                dbHost,
                dbPort,
                dbisSSL,
                dbName,
                dbUser,
                dbPass
        );
    }
}
