package org.secverse.queueSystem;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.secverse.queueSystem.DB.DBManager;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@Plugin(id = "secverse_queue", name = "QueueSystem", version = "1.0-SNAPSHOT", description = "A custom SecVerse Queue for Velocity", authors = {"Mia_conmeow"})
public class QueueSystem {

    private final ProxyServer server;
    private final Path dataDirectory;
    private CommentedConfigurationNode config;
    private final Logger logger;
    private DBManager db;
    private ScheduledTask task;
    private PluginContainer pluginContainer;

    private final Queue<UUID> premiumQueue = new LinkedList<UUID>();
    private final Queue<UUID> vipQueue = new LinkedList<UUID>();
    private final Queue<UUID> defaultQueue = new LinkedList<UUID>();
    private final Queue<UUID> softbanQueue = new LinkedList<UUID>();

    // Track when softban players joined
    private final Map<UUID, Instant> softbanJoinTimes = new ConcurrentHashMap<>();

    private boolean enableLP, premiumEnabled, vipEnabled, defaultEnabled, softbanEnabled;
    public static String displayname_softban, displayname_defaultgrp, displayname_vip,
            displayname_premium, softban, defaultgrp, vip, premium, limboServer, targetServer,
            RestartMessage, JoinMessage, ConnectingMessage;



    @Inject
    public QueueSystem(ProxyServer server,
                       Logger logger,
                       @DataDirectory Path dataDirectory,
                       PluginContainer lpluginContainer) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.pluginContainer = lpluginContainer;

    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        loadConfig();
        logger.info("[QueueSystem] Loaded config. Limbo: " + limboServer + ", Target: " + targetServer);
        server.getEventManager().register(pluginContainer, this);
        task = server.getScheduler()
                .buildTask(pluginContainer, this::tick)
                .repeat(5, TimeUnit.SECONDS)
                .schedule();
    }

    private void loadConfig() {
        try {
            Path configPath = dataDirectory.resolve("config.yml");
            YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
                    .path(configPath)
                    .build();
            config = loader.load();

            enableLP       = config.node("enablelp").getBoolean();
            limboServer      = config.node("limboserver").getString("queue");
            targetServer     = config.node("targetserver").getString("survival");

            // Set defaults if missing
            config.node("enablelp").set(enableLP);
            config.node("limboserver").set(limboServer);
            config.node("targetserver").set(targetServer);

            // Groups
            ConfigurationNode groups = config.node("Groups");

            softban    = groups.node("softban").getString("badgroup");
            defaultgrp = groups.node("default").getString("default");
            vip        = groups.node("vip").getString("vip");
            premium    = groups.node("premium").getString("premium");


            groups.node("softban").set(softban);
            groups.node("default").set(defaultgrp);
            groups.node("vip").set(vip);
            groups.node("premium").set(premium);

            displayname_softban    = groups.node("displayname_softban").getString("badgroup");
            displayname_defaultgrp = groups.node("displayname_default").getString("default");
            displayname_vip        = groups.node("displayname_vip").getString("vip");
            displayname_premium    = groups.node("displayname_premium").getString("premium");


            groups.node("displayname_softban").set(displayname_softban);
            groups.node("displayname_default").set(displayname_defaultgrp);
            groups.node("displayname_vip").set(displayname_vip);
            groups.node("displayname_premium").set(displayname_premium);

            // Group Settings
            ConfigurationNode settingsMain = config.node("QueueGroupSettings");
            premiumEnabled  = settingsMain.node("premiumQueueEnabled").getBoolean(true);
            vipEnabled      = settingsMain.node("vipQueueEnabled").getBoolean(true);
            defaultEnabled  = settingsMain.node("QueueEnabled").getBoolean(true);
            softbanEnabled  = settingsMain.node("SoftbanQueueEnabled").getBoolean(true);
            // Datenbank
            ConfigurationNode dbNode = config.node("database");

            String host = dbNode.node("host").getString("127.0.0.1");
            int    port = dbNode.node("port").getInt(3306);
            String name = dbNode.node("name").getString("luckperms");
            String user = dbNode.node("user").getString("root");
            String pass = dbNode.node("pass").getString("");

            dbNode.node("host").set(host);
            dbNode.node("port").set(port);
            dbNode.node("name").set(name);
            dbNode.node("user").set(user);
            dbNode.node("pass").set(pass);

            if(enableLP) {
                db = new DBManager(logger, host, port, name, user, pass);
                db.connect();
            }

            loader.save(config);
            logger.info("Loaded");
        } catch (Exception e) {
            logger.info("[QueueSystem] Failed to load or create config.yml");
        }
    }


    private void enqueue(Player player) {
        UUID id = player.getUniqueId();
        remove(id);

        if(!enableLP) {
            defaultQueue.offer(id);
            return;
        }
        // Prüfe Premium Gruppe
        if (premiumEnabled && db.isPlayerInGroup(id, "group." + premium)) {
            premiumQueue.offer(id);
            return; // bereits einsortiert, fertig
        }

        // Prüfe VIP Gruppe
        if (vipEnabled && db.isPlayerInGroup(id, "group." + vip)) {
            vipQueue.offer(id);
            return;
        }

        // Prüfe Softban Gruppe
        if (softbanEnabled && db.isPlayerInGroup(id, "group." + softban)) {
            softbanQueue.offer(id);
            softbanJoinTimes.put(id, Instant.now());
            return;
        }

        if (defaultEnabled) {
            defaultQueue.offer(id);
        }
    }


    private void remove(UUID id) {
        premiumQueue.remove(id);
        vipQueue.remove(id);
        defaultQueue.remove(id);
        softbanQueue.remove(id);
        softbanJoinTimes.remove(id);
    }

    private int getPosition(UUID id) {
        List<UUID> list = new ArrayList<>();
        list.addAll(premiumQueue); list.addAll(vipQueue); list.addAll(defaultQueue);
        softbanQueue.stream()
                .filter(uuid -> Duration.between(softbanJoinTimes.get(uuid), Instant.now()).toMinutes() >= 5)
                .forEach(list::add);
        return list.indexOf(id) + 1;
    }

    private UUID pollNext() {
        if (premiumEnabled && !premiumQueue.isEmpty()) return premiumQueue.poll();
        if (vipEnabled && !vipQueue.isEmpty()) return vipQueue.poll();
        if (defaultEnabled && !defaultQueue.isEmpty()) return defaultQueue.poll();
        if (softbanEnabled && premiumQueue.isEmpty() && vipQueue.isEmpty() && defaultQueue.isEmpty()) {
            UUID id = softbanQueue.peek();
            if (id != null && Duration.between(softbanJoinTimes.get(id), Instant.now()).toMinutes() >= 5) {
                softbanJoinTimes.remove(id);
                return softbanQueue.poll();
            }
        }
        return null;
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        Player player = event.getPlayer();
        String serverName = event.getServer().getServerInfo().getName();
        if (!serverName.equals(limboServer)) return;
        UUID id = player.getUniqueId();
        enqueue(player);
        int pos = getPosition(id);

        String queueName;
        if (premiumQueue.contains(id)) {
            queueName = displayname_premium;
        } else if (vipQueue.contains(id)) {
            queueName = displayname_vip;
        } else if (softbanQueue.contains(id)) {
            queueName = displayname_softban;
        } else {
            queueName = displayname_defaultgrp;
        }
        player.sendMessage(Component.text("You have been added to the " + queueName + " queue. Position: " + pos)
                .color(NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
    }

    /**
     * Process the queue: connect each enqueued player to the target server.
     */
    private void tick() {
        RegisteredServer target = server.getServer(targetServer).orElse(null);
        boolean online = target != null &&
                target.ping().handle((pong, ex) -> ex == null).join();
        if (!online) {
            // Server offline: status und Position in Warteschlange senden
            List<UUID> combined = new ArrayList<>();
            combined.addAll(premiumQueue);
            combined.addAll(vipQueue);
            combined.addAll(defaultQueue);
            softbanQueue.stream()
                    .filter(u -> Duration.between(softbanJoinTimes.get(u), Instant.now()).toMinutes() >= 5)
                    .forEach(combined::add);
            for (int i = 0; i < combined.size(); i++) {
                UUID id = combined.get(i);
                int finalI = i;
                server.getPlayer(id).ifPresent(player -> {
                    String queueName;
                    if (premiumQueue.contains(id)) queueName = displayname_premium;
                    else if (vipQueue.contains(id)) queueName = displayname_vip;
                    else if (defaultQueue.contains(id)) queueName = displayname_defaultgrp;
                    else queueName = "Softban";
                    player.sendMessage(Component.text("Server " + targetServer + " is offline. Your now in the " + queueName + "-Queue, Position " + (finalI + 1) + ".")
                            .color(NamedTextColor.RED).decorate(TextDecoration.BOLD));
                });
            }
            return;
        }
        UUID next = pollNext();
        if (next == null) return;
        server.getPlayer(next).ifPresent(player -> {
            player.createConnectionRequest(target).fireAndForget();
            player.sendMessage(Component.text("Connecting...")
                    .color(NamedTextColor.GOLD));
        });
    }

    @Subscribe
    public void onKick(KickedFromServerEvent event) {
        if (!event.getServer().getServerInfo().getName().equalsIgnoreCase(targetServer)) return;
        String reason = event.getServerKickReason().map(Component::toString).orElse("").toLowerCase();
        if (reason.contains("restarting") || reason.contains("closed")) {
            remove(event.getPlayer().getUniqueId());
            enqueue(event.getPlayer());
            RegisteredServer limbo = server.getServer(limboServer)
                    .orElseThrow(() -> new IllegalStateException("Limbo server not found"));
            event.setResult(KickedFromServerEvent.RedirectPlayer.create(limbo,
                    Component.text("Server restarting, queued...")
                            .color(NamedTextColor.RED).decorate(TextDecoration.BOLD)));
        }
    }


    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        remove(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (task != null) task.cancel();
        if (db != null) db.disconnect();
        clearAll();
    }

    private void clearAll() {
        premiumQueue.clear(); vipQueue.clear(); defaultQueue.clear(); softbanQueue.clear(); softbanJoinTimes.clear();
    }
}
