package org.secverse.queueSystem.QueueWorker.policy;

import java.util.Queue;
import java.util.UUID;

public interface WeightPolicy {

    UUID selectNext(
            Queue<UUID> premium,
            Queue<UUID> vip,
            Queue<UUID> def,
            Queue<UUID> softbanEligible
    );
}
