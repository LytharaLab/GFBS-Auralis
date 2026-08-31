package org.lytharalab.gfbs.auralis.core.source;

import org.lytharalab.gfbs.auralis.api.source.AudioDataSource;
import org.lytharalab.gfbs.auralis.api.source.AudioDataSourceFactory;
import org.lytharalab.gfbs.auralis.api.source.AudioDataSourceRegistry;
import org.lytharalab.gfbs.auralis.api.source.AudioSourceRequest;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AudioDataSourceRegistryImpl implements AudioDataSourceRegistry {
    private final Map<String, AudioDataSourceFactory> factories = new ConcurrentHashMap<>();

    @Override
    public void register(String typeId, AudioDataSourceFactory factory) {
        String id = normalize(typeId);
        AudioDataSourceFactory previous = factories.putIfAbsent(id, Objects.requireNonNull(factory, "factory"));
        if (previous != null && previous != factory) {
            throw new IllegalArgumentException("Audio data source type already registered: " + id);
        }
    }

    @Override public boolean unregister(String typeId) { return factories.remove(normalize(typeId)) != null; }
    @Override public boolean contains(String typeId) { return factories.containsKey(normalize(typeId)); }
    @Override public Set<String> types() { return Set.copyOf(factories.keySet()); }

    @Override
    public AudioDataSource create(String typeId, AudioSourceRequest request) throws Exception {
        String id = normalize(typeId);
        AudioDataSourceFactory factory = factories.get(id);
        if (factory == null) throw new IllegalArgumentException("Unknown audio data source type: " + id);
        AudioDataSource source = Objects.requireNonNull(factory.create(Objects.requireNonNull(request, "request")),
                "Audio data source factory returned null: " + id);
        try {
            // Validate immutable metadata before ownership crosses into the engine.
            Objects.requireNonNull(source.format(), "Audio data source returned null format");
            Objects.requireNonNull(source.mode(), "Audio data source returned null mode");
            if (source.mode() == org.lytharalab.gfbs.auralis.api.source.AudioSourceMode.TIMELINE
                    && !source.isSeekable()) {
                throw new IllegalArgumentException("TIMELINE audio data source must be seekable: " + id);
            }
            return source;
        } catch (Throwable failure) {
            try { source.close(); } catch (Throwable closeFailure) { failure.addSuppressed(closeFailure); }
            if (failure instanceof Error error) throw error;
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw (Exception) failure;
        }
    }

    public static String normalize(String raw) {
        String id = Objects.requireNonNull(raw, "typeId").trim().toLowerCase(Locale.ROOT);
        if (id.length() > 128 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Audio data source id must be namespaced: " + raw);
        }
        return id;
    }
}
