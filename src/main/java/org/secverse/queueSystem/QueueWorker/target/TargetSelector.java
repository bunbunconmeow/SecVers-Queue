package org.secverse.queueSystem.QueueWorker.target;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.List;
import java.util.Optional;

/**
 * Strategy for selecting a target RegisteredServer from a configured list of names.
 */
public interface TargetSelector {

    Optional<RegisteredServer> pickBestTarget(ProxyServer proxy, List<String> targetNames);
}
