package org.lytharalab.gfbs.auralis.api;

import java.util.UUID;

public record ClientExecutionResult(UUID playerId, ClientExecutionStatus status, String detail, double durationSeconds) { }
