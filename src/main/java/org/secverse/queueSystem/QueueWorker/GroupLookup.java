package org.secverse.queueSystem.QueueWorker;
import java.util.UUID;

public interface GroupLookup {
    boolean isInGroup(UUID playerId, String groupName);
    boolean isEnabled();
}
