package org.secverse.queueSystem.QueueWorker.target;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.List;
import java.util.Optional;

public interface TargetSelector {

    Optional<RegisteredServer> pickBestTarget(ProxyServer proxy, List<String> targetNames);
}
