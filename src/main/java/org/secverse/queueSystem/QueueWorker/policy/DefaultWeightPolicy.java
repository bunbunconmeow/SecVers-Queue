package org.secverse.queueSystem.QueueWorker.policy;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class DefaultWeightPolicy implements WeightPolicy {

    private final int wPremium;
    private final int wVip;
    private final int wDefault;

    private final AtomicInteger iPremium = new AtomicInteger(0);
    private final AtomicInteger iVip = new AtomicInteger(0);
    private final AtomicInteger iDefault = new AtomicInteger(0);

    public DefaultWeightPolicy(int wPremium, int wVip, int wDefault) {
        if (wPremium <= 0 || wVip <= 0 || wDefault <= 0) {
            throw new IllegalArgumentException("Weights must be > 0");
        }
        this.wPremium = wPremium;
        this.wVip = wVip;
        this.wDefault = wDefault;
    }

    @Override
    public UUID selectNext(Queue<UUID> premium, Queue<UUID> vip, Queue<UUID> def, Queue<UUID> softbanEligible) {
        for (int attempt = 0; attempt < wPremium + wVip + wDefault; attempt++) {
            int p = iPremium.getAndIncrement();
            if (!premium.isEmpty() && p % (wPremium + wVip + wDefault) < wPremium) {
                UUID id = premium.poll();
                if (id != null) return id;
            }
            int v = iVip.getAndIncrement();
            if (!vip.isEmpty() && v % (wPremium + wVip + wDefault) < wVip) {
                UUID id = vip.poll();
                if (id != null) return id;
            }
            int d = iDefault.getAndIncrement();
            if (!def.isEmpty() && d % (wPremium + wVip + wDefault) < wDefault) {
                UUID id = def.poll();
                if (id != null) return id;
            }
        }

        if (premium.isEmpty() && vip.isEmpty() && def.isEmpty()) {
            return softbanEligible.poll();
        }
        return null;
    }
}
