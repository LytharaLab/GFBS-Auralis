package org.lytharalab.gfbs.auralis.api;

import java.util.Map;
import java.util.UUID;

public record ServerOperationResult(UUID operationId, AuthoritativeSoundSnapshot snapshot,
                                    Map<UUID, ClientExecutionResult> clients) {
    public ServerOperationResult { clients = Map.copyOf(clients); }

    public boolean allApplied() {
        return clients.values().stream().allMatch(result -> result.status() == ClientExecutionStatus.APPLIED
                || result.status() == ClientExecutionStatus.STALE);
    }
}
