package org.secverse.queueSystem.QueueWorker.target;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class DefaultTargetSelector implements TargetSelector {

    private final Map<String, Integer> recentAttempts = new ConcurrentHashMap<>();
    private final Random rr = new Random();

    @Override
    public Optional<RegisteredServer> pickBestTarget(ProxyServer proxy, List<String> targetNames) {
        List<RegisteredServer> alive = new ArrayList<>();
        for (String name : targetNames) {
            proxy.getServer(name).ifPresent(server -> {
                boolean ok = server.ping().handle((pong, ex) -> ex == null).orTimeout(1500, TimeUnit.MILLISECONDS).exceptionally(t -> false).join();
                if (ok) alive.add(server);
            });
        }
        if (alive.isEmpty()) return Optional.empty();

        alive.sort(Comparator.comparingInt(s -> recentAttempts.getOrDefault(s.getServerInfo().getName(), 0)));
        RegisteredServer picked = alive.get(0);

        recentAttempts.merge(picked.getServerInfo().getName(), 1, Integer::sum);
        decayAttemptsIfNeeded();
        return Optional.of(picked);
    }

    private void decayAttemptsIfNeeded() {
        if (recentAttempts.size() > 64) {
            recentAttempts.replaceAll((k, v) -> Math.max(0, v - 1));
        }
    }
}
