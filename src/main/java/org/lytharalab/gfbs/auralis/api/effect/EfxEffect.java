package org.lytharalab.gfbs.auralis.api.effect;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Configurable native OpenAL EFX effect. One instance is compiled to one
 * engine-owned effect object and auxiliary slot, shared by every source routed
 * through the owning bus.
 */
public final class EfxEffect extends AbstractAuralisEffect {
    private final EfxEffectType type;
    private final Object parameterLock = new Object();
    private final EnumMap<EfxParameter, Object> parameters = new EnumMap<>(EfxParameter.class);
    private volatile EfxFilter sendFilter;

    public EfxEffect(String id, EfxEffectType type) {
        super(id);
        this.type = Objects.requireNonNull(type, "type");
        for (EfxParameter parameter : EfxParameter.values()) {
            if (parameter.effectType() == type) {
                parameters.put(parameter, parameter.defaultValue());
            }
        }
    }

    @Override
    public EffectBackend getBackend() {
        return EffectBackend.OPENAL_EFX;
    }

    public EfxEffectType getType() {
        return type;
    }

    public EfxEffect setFloat(EfxParameter parameter, float value) {
        require(parameter, EfxValueKind.FLOAT);
        synchronized (parameterLock) {
            Float newValue = parameter.clampFloat(value);
            if (!newValue.equals(parameters.put(parameter, newValue))) markChanged();
        }
        return this;
    }

    public EfxEffect setInt(EfxParameter parameter, int value) {
        require(parameter, EfxValueKind.INTEGER);
        synchronized (parameterLock) {
            Integer newValue = parameter.clampInt(value);
            if (!newValue.equals(parameters.put(parameter, newValue))) markChanged();
        }
        return this;
    }

    public EfxEffect setVector3(EfxParameter parameter, float x, float y, float z) {
        require(parameter, EfxValueKind.VECTOR3);
        float[] newValue = new float[] {clampUnit(x), clampUnit(y), clampUnit(z)};
        synchronized (parameterLock) {
            float[] old = (float[]) parameters.put(parameter, newValue);
            if (old == null || Float.compare(old[0], newValue[0]) != 0
                    || Float.compare(old[1], newValue[1]) != 0
                    || Float.compare(old[2], newValue[2]) != 0) {
                markChanged();
            }
        }
        return this;
    }

    public EfxEffect reset(EfxParameter parameter) {
        require(parameter, parameter.kind());
        synchronized (parameterLock) {
            parameters.put(parameter, parameter.defaultValue());
            markChanged();
        }
        return this;
    }

    public Object get(EfxParameter parameter) {
        require(parameter, parameter.kind());
        synchronized (parameterLock) {
            Object value = parameters.get(parameter);
            return value instanceof float[] vector ? vector.clone() : value;
        }
    }

    /** Immutable, defensive parameter snapshot used by the audio thread. */
    public Map<EfxParameter, Object> parameters() {
        synchronized (parameterLock) {
            Map<EfxParameter, Object> copy = new LinkedHashMap<>();
            parameters.forEach((parameter, value) ->
                    copy.put(parameter, value instanceof float[] vector ? vector.clone() : value));
            return Collections.unmodifiableMap(copy);
        }
    }

    public EfxFilter getSendFilter() {
        return sendFilter;
    }

    public EfxEffect setSendFilter(EfxFilter filter) {
        if (sendFilter != filter) {
            sendFilter = filter;
            markChanged();
        }
        return this;
    }

    @Override
    public EfxEffect setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        return this;
    }

    @Override
    public EfxEffect setWet(float wet) {
        super.setWet(wet);
        return this;
    }

    private void require(EfxParameter parameter, EfxValueKind expected) {
        Objects.requireNonNull(parameter, "parameter");
        if (parameter.effectType() != type) {
            throw new IllegalArgumentException(parameter + " belongs to " + parameter.effectType() + ", not " + type);
        }
        if (parameter.kind() != expected) {
            throw new IllegalArgumentException(parameter + " is " + parameter.kind() + ", not " + expected);
        }
    }

    private static float clampUnit(float value) {
        float finite = Float.isFinite(value) ? value : 0.0f;
        return Math.max(-1.0f, Math.min(1.0f, finite));
    }
}
