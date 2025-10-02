package org.secverse.queueSystem.QueueWorker;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.secverse.queueSystem.Helper.ConfigData;
import org.secverse.queueSystem.Helper.GlobalCache;
import org.secverse.queueSystem.QueueWorker.policy.WeightPolicy;
import org.secverse.queueSystem.QueueWorker.target.TargetSelector;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;


public final class Worker {

    private final ProxyServer proxy;
    private final GroupLookup groups;
    private final WeightPolicy policy;
    private final TargetSelector targetSelector;

    // Queues per tier
    private final Queue<UUID> qPremium = new ConcurrentLinkedQueue<>();
    private final Queue<UUID> qVip = new ConcurrentLinkedQueue<>();
    private final Queue<UUID> qDefault = new ConcurrentLinkedQueue<>();
    private final Queue<UUID> qSoftban = new ConcurrentLinkedQueue<>();

    // Presence and metadata
    private final Set<UUID> present = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Instant> softbanJoined = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> lastMessageAt = new ConcurrentHashMap<>();

    public Worker(ProxyServer proxy, GroupLookup groups, WeightPolicy policy, TargetSelector targetSelector) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.groups = Objects.requireNonNull(groups, "groups");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.targetSelector = Objects.requireNonNull(targetSelector, "targetSelector");
    }


    public void enqueue(Player player) {
        UUID id = player.getUniqueId();
        final ConfigData cfg = GlobalCache.get();

        final String currentServer = player.getCurrentServer()
                .map(s -> s.getServerInfo().getName())
                .orElse("");

        final boolean isOnTarget = cfg.getTargetServers().stream()
                .anyMatch(n -> n.equalsIgnoreCase(currentServer));
        if (isOnTarget) {
            return;
        }
        if (!currentServer.equalsIgnoreCase(cfg.getLimboServer())) {
            return;
        }

        removeFromAllQueues(id);

        if (!present.add(id)) {
            return;
        }

        if (!groups.isEnabled()) {
            qDefault.offer(id);
            return;
        }

        if (groups.isInGroup(id, "group." + "premium")) { // optional helper if you map names elsewhere
            qPremium.offer(id);
            return;
        }

        if (groups.isInGroup(id, "group." + "vip")) {
            qVip.offer(id);
            return;
        }

        if (groups.isInGroup(id, "group." + "badgroup")) {
            qSoftban.offer(id);
            softbanJoined.put(id, Instant.now());
            return;
        }

        qDefault.offer(id);
    }

    /**
     * Removes a player from all queues and clears metadata.
     */
    public void remove(Player player) {
        removeFromAllQueues(player.getUniqueId());
    }

    /**
     * Returns the 1-based visible position of the player across all eligible queues.
     */
    public int getPosition(UUID id) {
        List<UUID> combined = combinedEligibleOrder();
        int idx = combined.indexOf(id);
        return idx >= 0 ? idx + 1 : combined.size() + 1;
    }

    /**
     * Periodic worker tick:
     * - Select best target
     * - Dequeue a batch according to policy
     * - Connect players and send feedback messages with cooldown
     */
    public void tick() {
        ConfigData cfg = GlobalCache.get();
        Optional<RegisteredServer> targetOpt = targetSelector.pickBestTarget(proxy, cfg.getTargetServers());
        if (targetOpt.isEmpty()) {
            notifyAllQueuedOffline(cfg);
            return;
        }

        RegisteredServer target = targetOpt.get();

        int maxBatch = Math.max(1, cfg.getDequeueMaxBatch());
        for (int i = 0; i < maxBatch; i++) {
            UUID next = selectNextEligible();
            if (next == null) break;

            removeFromAllQueues(next);

            proxy.getPlayer(next).ifPresent(p -> {
                p.createConnectionRequest(target).fireAndForget();
                p.sendMessage(Component.text("Connecting...").color(NamedTextColor.GOLD));
            });
        }
    }

    /**
     * Selects the next eligible UUID using current policy and softban eligibility.
     */
    private UUID selectNextEligible() {
        Queue<UUID> eligibleSoftban = new ArrayDeque<>();
        Duration minWait = GlobalCache.get().getSoftbanMinWait();

        for (UUID id : qSoftban) {
            Instant at = softbanJoined.getOrDefault(id, Instant.EPOCH);
            if (Duration.between(at, Instant.now()).compareTo(minWait) >= 0) {
                eligibleSoftban.offer(id);
            } else {
                break; // qSoftban is FIFO; early exit is fine
            }
        }

        return policy.selectNext(qPremium, qVip, qDefault, eligibleSoftban);
    }

    /**
     * Notifies all queued players that no target is online, with per-player cooldown.
     */
    private void notifyAllQueuedOffline(ConfigData cfg) {
        List<UUID> combined = combinedEligibleOrder();
        for (int i = 0; i < combined.size(); i++) {
            UUID id = combined.get(i);
            if (!shouldNotify(id, Duration.ofSeconds(10))) continue;
            int pos = i + 1;
            proxy.getPlayer(id).ifPresent(p -> p.sendMessage(
                    Component.text("All targets are offline. You are in queue, position " + pos + ".")
                            .color(NamedTextColor.RED).decorate(TextDecoration.BOLD)
            ));
            lastMessageAt.put(id, Instant.now());
        }
    }


    private List<UUID> combinedEligibleOrder() {
        List<UUID> out = new ArrayList<>(qPremium.size() + qVip.size() + qDefault.size() + qSoftban.size());
        out.addAll(qPremium);
        out.addAll(qVip);
        out.addAll(qDefault);

        Duration minWait = GlobalCache.get().getSoftbanMinWait();
        for (UUID id : qSoftban) {
            Instant at = softbanJoined.getOrDefault(id, Instant.EPOCH);
            if (Duration.between(at, Instant.now()).compareTo(minWait) >= 0) {
                out.add(id);
            }
        }
        return out;
    }

    private boolean shouldNotify(UUID id, Duration minInterval) {
        Instant last = lastMessageAt.getOrDefault(id, Instant.MIN);
        return Duration.between(last, Instant.now()).compareTo(minInterval) >= 0;
    }

    private void removeFromAllQueues(UUID id) {
        qPremium.remove(id);
        qVip.remove(id);
        qDefault.remove(id);
        qSoftban.remove(id);
        present.remove(id);
        lastMessageAt.remove(id);
        softbanJoined.remove(id);
    }
}
