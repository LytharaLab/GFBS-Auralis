package org.lytharalab.gfbs.auralis.core.effect;

import org.lytharalab.gfbs.auralis.api.effect.AuralisEffectFactory;
import org.lytharalab.gfbs.auralis.api.effect.AuralisEffectRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class AuralisEffectRegistryImpl implements AuralisEffectRegistry {
    private final ConcurrentHashMap<String, AuralisEffectFactory> factories = new ConcurrentHashMap<>();

    @Override
    public void register(String typeId, AuralisEffectFactory factory) {
        String id = validateId(typeId);
        Objects.requireNonNull(factory, "factory");
        AuralisEffectFactory old = factories.putIfAbsent(id, factory);
        if (old != null && old != factory) {
            throw new IllegalArgumentException("Auralis effect type is already registered: " + id);
        }
    }

    @Override
    public boolean unregister(String typeId) {
        return factories.remove(validateId(typeId)) != null;
    }

    @Override
    public Optional<AuralisEffectFactory> find(String typeId) {
        if (typeId == null) return Optional.empty();
        return Optional.ofNullable(factories.get(typeId.trim().toLowerCase(java.util.Locale.ROOT)));
    }

    @Override
    public Map<String, AuralisEffectFactory> factories() {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(factories));
    }

    private static String validateId(String id) {
        String value = Objects.requireNonNull(id, "typeId").trim().toLowerCase(java.util.Locale.ROOT);
        if (value.isEmpty() || value.length() > 128 || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Effect type id must be namespaced (namespace:path): " + id);
        }
        return value;
    }
}
