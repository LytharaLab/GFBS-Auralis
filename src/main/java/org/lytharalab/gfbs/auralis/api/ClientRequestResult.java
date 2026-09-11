package org.lytharalab.gfbs.auralis.api;

import java.util.UUID;

public record ClientRequestResult(UUID requestId, boolean applied, String detail) { }
