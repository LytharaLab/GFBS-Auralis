package org.lytharalab.gfbs.auralis.api.source;

import java.util.Map;
import java.util.Objects;

/** Immutable arguments passed to a registered custom source factory. */
public record AudioSourceRequest(String resource, Map<String, ?> parameters) {
    public AudioSourceRequest {
        resource = Objects.requireNonNull(resource, "resource");
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
    }

    public AudioSourceRequest(String resource) {
        this(resource, Map.of());
    }
}
