package org.secverse.queueSystem.Helper;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;


public final class SkipToken {
    private final String code;
    private final Instant issuedAt;
    private final Instant expiresAt;
    private volatile boolean consumed;
    private volatile UUID consumedBy;

    public SkipToken(String code, Instant issuedAt, Instant expiresAt) {
        this.code = Objects.requireNonNull(code);
        this.issuedAt = Objects.requireNonNull(issuedAt);
        this.expiresAt = Objects.requireNonNull(expiresAt);
    }

    public String getCode() { return code; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }
    public boolean isConsumed() { return consumed; }
    public UUID getConsumedBy() { return consumedBy; }

    public synchronized boolean consume(UUID playerId) {
        if (consumed || isExpired()) return false;
        consumed = true;
        consumedBy = playerId;
        return true;
    }
}
