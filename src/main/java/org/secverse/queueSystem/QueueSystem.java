package org.secverse.queueSystem;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.secverse.queueSystem.DB.DBManager;
import org.secverse.queueSystem.QueueWorker.GroupLookup;
import org.secverse.queueSystem.QueueWorker.Worker;
import org.secverse.queueSystem.QueueWorker.policy.DefaultWeightPolicy;
import org.secverse.queueSystem.QueueWorker.policy.WeightPolicy;
import org.secverse.queueSystem.QueueWorker.target.DefaultTargetSelector;
import org.secverse.queueSystem.QueueWorker.target.TargetSelector;
import org.secverse.queueSystem.SecVersCom.Telemetry;
import org.secverse.queueSystem.SecVersCom.UpdateChecker;
import org.secverse.queueSystem.Helper.ConfigData;
import org.secverse.queueSystem.Helper.ConfigManager;
import org.secverse.queueSystem.Helper.GlobalCache;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;



@Plugin(id = "secverse_queue", name = "QueueSystem", version = "3.0", description = "A SecVerse Queue for Velocity", authors = {"Mia_conmeow"})
public class QueueSystem {
    private final ProxyServer server;
    private final Path dataDirectory;
    private final Logger logger;

    // Optional external systems
    private DBManager db;
    private UpdateChecker updateChecker;
    private Telemetry telemetry;

    // Core queue worker
    private Worker worker;

    // Config manager (creates and reloads config.yml)
    private ConfigManager cfgManager;

    @Inject
    public QueueSystem(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        // Load configuration into the global cache
        cfgManager = new ConfigManager(dataDirectory.resolve("config.yml"), logger);
        ConfigData cfg = cfgManager.loadOrCreate();
        GlobalCache.initialize(cfg);

        // Allow external reloads (e.g., via command) to update the global snapshot and re-tune scheduling
        cfgManager.addReloadListener(newCfg -> {
            GlobalCache.replace(newCfg);
            rescheduleTick(); // adapt tick cadence on-the-fly if interval changed
            logger.info("[QueueSystem] Configuration hot-reloaded");
        });

        // 2) Initialize optional DB lookup (LuckPerms-style) if enabled
        if (cfg.isEnableLuckPerms()) {
            db = new DBManager(
                    logger,
                    cfg.getDbHost(),
                    cfg.getDbPort(),
                    cfg.getDbName(),
                    cfg.getDBisSSL(),
                    cfg.getDbUser(),
                    cfg.getDbPass()
            );
            db.connect();
        }

        // Optional update check and telemetry
        if (cfg.isCheckUpdate()) {
            updateChecker = new UpdateChecker(this, server, logger, null);
            updateChecker.checkNowAsync();
        }
        if (cfg.isEnableTelemetry()) {
            telemetry = new Telemetry(this, server, logger, dataDirectory.toFile(), true);
            Map<String, Object> extra = new HashMap<>(); extra.put("onlinePlayers", server.getPlayerCount()); extra.put("javaVersion", System.getProperty("java.version"));
            telemetry.sendTelemetryAsync(
                    "VelocityQueue",
                    getClass().getAnnotation(Plugin.class).version(),
                    extra
            );
        }

        // Build queue worker: provide group lookup, weight policy and target selector
        GroupLookup groupLookup = new GroupLookup() {
            @Override
            public boolean isInGroup(UUID playerId, String groupName) {
                return db != null && db.isPlayerInGroup(playerId, groupName);
            }
            @Override
            public boolean isEnabled() {
                return db != null;
            }
        };

        // Weights may come from config in a more advanced setup; defaults are sensible
        WeightPolicy policy = new DefaultWeightPolicy(
                5, // premium
                3, // vip
                1  // default
        );

        TargetSelector targetSelector = new DefaultTargetSelector();

        worker = new Worker(server, groupLookup, policy, targetSelector);

        // Register this plugin as event listener
        server.getEventManager().register(this, this);

        // Start queue tick using configured interval
        scheduleTick();

        logger.info("[QueueSystem] Ready. Limbo=" + cfg.getLimboServer() + " Targets=" + cfg.getTargetServers());
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (db != null) db.disconnect();
    }

    @Subscribe
    public void onKick(KickedFromServerEvent event) {
        ConfigData cfg = GlobalCache.get();

        boolean fromTarget = cfg.getTargetServers().stream()
                .anyMatch(n -> n.equalsIgnoreCase(event.getServer().getServerInfo().getName()));
        if (!fromTarget) return;


        server.getServer(cfg.getLimboServer()).ifPresent(l ->
                event.setResult(KickedFromServerEvent.RedirectPlayer.create(
                        l,
                        Component.text("Server unavailable. Redirecting to limbo...")
                                .color(NamedTextColor.RED).decorate(TextDecoration.BOLD)
                ))
        );
    }


    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        ConfigData cfg = GlobalCache.get();
        String joined = event.getServer().getServerInfo().getName();

        if (cfg.getTargetServers().stream().anyMatch(n -> n.equalsIgnoreCase(joined))) {
            return;
        }

        if (!joined.equalsIgnoreCase(cfg.getLimboServer())) return;

        Player p = event.getPlayer();
        worker.enqueue(p);

        int pos = worker.getPosition(p.getUniqueId());
        p.sendMessage(Component.text("You have been added to the queue. Position: " + pos)
                .color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
    }


    private void scheduleTick() {
        Duration interval = GlobalCache.get().getDequeueInterval();
        long seconds = Math.max(1, interval.getSeconds());
        server.getScheduler()
                .buildTask(this, worker::tick)
                .repeat(seconds, TimeUnit.SECONDS)
                .schedule();
    }

    private void rescheduleTick() {
        scheduleTick();
    }
}
