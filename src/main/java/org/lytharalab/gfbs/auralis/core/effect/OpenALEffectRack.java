package org.lytharalab.gfbs.auralis.core.effect;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.system.MemoryStack;
import org.lytharalab.gfbs.auralis.GFBsAuralis;
import org.lytharalab.gfbs.auralis.api.effect.AuralisEffect;
import org.lytharalab.gfbs.auralis.api.effect.EfxDirectFilterEffect;
import org.lytharalab.gfbs.auralis.api.effect.EfxEffect;
import org.lytharalab.gfbs.auralis.api.effect.EfxFilter;
import org.lytharalab.gfbs.auralis.api.effect.EfxFilterParameter;
import org.lytharalab.gfbs.auralis.api.effect.EfxParameter;
import org.lytharalab.gfbs.auralis.api.effect.EfxValueKind;
import org.lytharalab.gfbs.auralis.api.effect.OpenALSourceEffect;
import org.lytharalab.gfbs.auralis.api.openal.OpenALAccess;
import org.lytharalab.gfbs.auralis.api.openal.OpenALSourceEffectContext;
import org.lytharalab.gfbs.auralis.core.bus.BusMixSnapshot;
import org.lytharalab.gfbs.auralis.core.bus.CompiledBusRoute;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles bus effects to shared OpenAL EFX objects and attaches routes to
 * physical sources. All methods ending in {@code OnALThread} must run with the
 * Auralis context current.
 */
public final class OpenALEffectRack implements AutoCloseable {
    private record EfxRuntime(int effectId, int slotId, long revision) {
        EfxRuntime withRevision(long value) { return new EfxRuntime(effectId, slotId, value); }
    }

    private record FilterRuntime(int filterId, long revision) {
        FilterRuntime withRevision(long value) { return new FilterRuntime(filterId, value); }
    }

    private record CustomBinding(OpenALSourceEffect effect, SourceContext context) {
    }

    private record SourceBinding(
            long signature,
            int auxiliarySends,
            boolean directFilter,
            List<Integer> slotIds,
            List<Integer> filterIds,
            List<CustomBinding> custom
    ) {
    }

    private record SourceContext(
            int sourceId,
            int auxiliarySendIndex,
            String busName,
            String effectId,
            OpenALAccess openAL
    ) implements OpenALSourceEffectContext {
    }

    private final OpenALAccess openAL;
    private final boolean supported;
    private final int maxAuxiliarySends;
    private final Map<EfxEffect, EfxRuntime> effects = new IdentityHashMap<>();
    private final Map<EfxFilter, FilterRuntime> filters = new IdentityHashMap<>();
    private final Map<Integer, SourceBinding> sourceBindings = new java.util.HashMap<>();
    private final Set<String> warned = new java.util.HashSet<>();
    private final Set<AuralisEffect> observedEffects = Collections.newSetFromMap(new IdentityHashMap<>());
    private Set<EfxEffect> activeEffects = Collections.newSetFromMap(new IdentityHashMap<>());
    private Set<EfxFilter> activeFilters = Collections.newSetFromMap(new IdentityHashMap<>());
    private long lastMixRevision = Long.MIN_VALUE;

    public OpenALEffectRack(OpenALAccess openAL) {
        this.openAL = java.util.Objects.requireNonNull(openAL, "openAL");
        this.supported = openAL.isEfxSupported();
        this.maxAuxiliarySends = Math.max(0, openAL.getMaxAuxiliarySends());
        if (!supported) {
            GFBsAuralis.LOGGER.info("OpenAL EFX is unavailable; Auralis bus gain/routing and PCM effects remain active");
        } else {
            GFBsAuralis.LOGGER.info("OpenAL EFX enabled (max auxiliary sends per source={})", maxAuxiliarySends);
        }
    }

    public boolean isSupported() { return supported; }
    public int getMaxAuxiliarySends() { return maxAuxiliarySends; }

    public void syncOnALThread(BusMixSnapshot mix) {
        if (!openAL.isOnAudioThread()) {
            throw new IllegalStateException("EFX rack sync must run on the Auralis OpenAL thread");
        }
        if (lastMixRevision == mix.revision()) return;

        Set<EfxEffect> nextActiveEffects = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<EfxFilter> nextActiveFilters = Collections.newSetFromMap(new IdentityHashMap<>());
        for (CompiledBusRoute route : mix.routes()) {
            for (AuralisEffect effect : route.effects()) {
                observedEffects.add(effect);
                try {
                    if (!effect.isEnabled() || effect.getWet() <= 0.0f) continue;
                    if (effect instanceof EfxEffect efx) {
                        if (!supported) continue;
                        nextActiveEffects.add(efx);
                        ensureEffect(efx);
                        if (efx.getSendFilter() != null) {
                            nextActiveFilters.add(efx.getSendFilter());
                            ensureFilter(efx.getSendFilter());
                        }
                    } else if (effect instanceof EfxDirectFilterEffect direct) {
                        if (supported) {
                            nextActiveFilters.add(direct.getFilter());
                            ensureFilter(direct.getFilter());
                        }
                    }
                } catch (Throwable failure) {
                    warnOnce(
                            "effect-sync:" + safeEffectId(effect),
                            "Effect '{}' failed during EFX synchronization and was bypassed: {}",
                            safeEffectId(effect), String.valueOf(failure.getMessage())
                    );
                }
            }
        }
        activeEffects = nextActiveEffects;
        activeFilters = nextActiveFilters;
        releaseUnusedRuntimes(activeEffects, activeFilters);
        lastMixRevision = mix.revision();
    }

    public void applyToSourceOnALThread(int sourceId, CompiledBusRoute route) {
        requireAudioThread();
        SourceBinding current = sourceBindings.get(sourceId);
        if (current != null && current.signature == route.effectSignature()) return;
        detachSourceOnALThread(sourceId);

        int sendIndex = 0;
        boolean directFilterAttached = false;
        List<Integer> slotIds = new ArrayList<>();
        List<Integer> filterIds = new ArrayList<>();
        List<CustomBinding> customBindings = new ArrayList<>();

        for (AuralisEffect effect : route.effects()) {
            try {
                if (!effect.isEnabled() || effect.getWet() <= 0.0f) continue;

                if (effect instanceof EfxDirectFilterEffect direct) {
                    if (!supported || directFilterAttached) continue;
                    FilterRuntime runtime = filters.get(direct.getFilter());
                    if (runtime != null) {
                        try {
                            clearAlError();
                            AL10.alSourcei(sourceId, EXTEfx.AL_DIRECT_FILTER, runtime.filterId());
                            throwIfAlError("attaching direct filter " + direct.getId());
                        } catch (Throwable failure) {
                            clearAlError();
                            AL10.alSourcei(sourceId, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
                            AL10.alGetError();
                            throw failure;
                        }
                        directFilterAttached = true;
                        filterIds.add(runtime.filterId());
                    }
                    continue;
                }

                if (effect instanceof EfxEffect efx) {
                    if (!supported) continue;
                    if (sendIndex >= maxAuxiliarySends) {
                        warnOnce(
                                "send-limit:" + route.busName() + ":" + route.signature(),
                                "Bus '{}' has more active EFX effects than this device can attach (limit={}); "
                                        + "child-bus and earlier chain entries take priority",
                                route.busName(), maxAuxiliarySends
                        );
                        continue;
                    }
                    EfxRuntime runtime = effects.get(efx);
                    if (runtime == null) continue;
                    int filterId = EXTEfx.AL_FILTER_NULL;
                    if (efx.getSendFilter() != null) {
                        FilterRuntime filter = filters.get(efx.getSendFilter());
                        if (filter != null) filterId = filter.filterId();
                    }
                    try {
                        clearAlError();
                        AL11.alSource3i(
                                sourceId,
                                EXTEfx.AL_AUXILIARY_SEND_FILTER,
                                runtime.slotId(),
                                sendIndex,
                                filterId
                        );
                        throwIfAlError("attaching EFX send " + efx.getId());
                    } catch (Throwable failure) {
                        clearAlError();
                        AL11.alSource3i(
                                sourceId,
                                EXTEfx.AL_AUXILIARY_SEND_FILTER,
                                EXTEfx.AL_EFFECTSLOT_NULL,
                                sendIndex,
                                EXTEfx.AL_FILTER_NULL
                        );
                        AL10.alGetError();
                        throw failure;
                    }
                    slotIds.add(runtime.slotId());
                    if (filterId != EXTEfx.AL_FILTER_NULL) filterIds.add(filterId);
                    sendIndex++;
                    continue;
                }

                if (effect instanceof OpenALSourceEffect custom) {
                    boolean reservesSend = custom.usesAuxiliarySend();
                    int suggestedSend = reservesSend && sendIndex < maxAuxiliarySends ? sendIndex : -1;
                    SourceContext context = new SourceContext(
                            sourceId, suggestedSend, route.busName(), safeEffectId(effect), openAL
                    );
                    try {
                        clearAlError();
                        custom.apply(context);
                        throwIfAlError("applying custom effect " + safeEffectId(effect));
                    } catch (Throwable failure) {
                        try {
                            clearAlError();
                            custom.detach(context);
                            throwIfAlError("rolling back custom effect " + safeEffectId(effect));
                        } catch (Throwable rollbackFailure) {
                            failure.addSuppressed(rollbackFailure);
                        }
                        throw failure;
                    }
                    customBindings.add(new CustomBinding(custom, context));
                    if (reservesSend && suggestedSend >= 0) sendIndex++;
                }
            } catch (Throwable failure) {
                warnOnce(
                        "effect-apply:" + safeEffectId(effect),
                        "Effect '{}' failed while attaching to a source and was bypassed: {}",
                        safeEffectId(effect), String.valueOf(failure.getMessage())
                );
            }
        }

        sourceBindings.put(sourceId, new SourceBinding(
                route.effectSignature(),
                sendIndex,
                directFilterAttached,
                List.copyOf(slotIds),
                List.copyOf(filterIds),
                List.copyOf(customBindings)
        ));
    }

    public void detachSourceOnALThread(int sourceId) {
        requireAudioThread();
        SourceBinding binding = sourceBindings.remove(sourceId);
        if (binding == null) {
            // Pooled sources can outlive a rack binding after a failed callback.
            if (supported) {
                clearAlError();
                AL10.alSourcei(sourceId, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
                AL10.alGetError();
            }
            releaseUnusedRuntimes(activeEffects, activeFilters);
            return;
        }

        for (CustomBinding custom : binding.custom()) {
            try {
                clearAlError();
                custom.effect().detach(custom.context());
                throwIfAlError("detaching custom effect " + safeEffectId(custom.effect()));
            } catch (Throwable failure) {
                warnOnce(
                        "custom-detach:" + safeEffectId(custom.effect()),
                        "Custom OpenAL effect '{}' failed during detach: {}",
                        safeEffectId(custom.effect()), String.valueOf(failure.getMessage())
                );
            }
        }

        if (supported) {
            for (int i = 0; i < binding.auxiliarySends(); i++) {
                try {
                    clearAlError();
                    AL11.alSource3i(
                            sourceId,
                            EXTEfx.AL_AUXILIARY_SEND_FILTER,
                            EXTEfx.AL_EFFECTSLOT_NULL,
                            i,
                            EXTEfx.AL_FILTER_NULL
                    );
                    throwIfAlError("detaching EFX send " + i);
                } catch (Throwable failure) {
                    warnOnce(
                            "send-detach:" + sourceId + ":" + i,
                            "Unable to detach EFX send {} from source {}: {}",
                            i, sourceId, String.valueOf(failure.getMessage())
                    );
                }
            }
            if (binding.directFilter()) {
                try {
                    clearAlError();
                    AL10.alSourcei(sourceId, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
                    throwIfAlError("detaching direct filter");
                } catch (Throwable failure) {
                    warnOnce(
                            "direct-detach:" + sourceId,
                            "Unable to detach direct filter from source {}: {}",
                            sourceId, String.valueOf(failure.getMessage())
                    );
                }
            }
        }
        releaseUnusedRuntimes(activeEffects, activeFilters);
    }

    public void closeOnALThread() {
        requireAudioThread();
        for (Integer sourceId : new ArrayList<>(sourceBindings.keySet())) {
            detachSourceOnALThread(sourceId);
        }

        if (supported) {
            for (EfxRuntime runtime : effects.values()) {
                try {
                    clearAlError();
                    EXTEfx.alAuxiliaryEffectSloti(runtime.slotId(), EXTEfx.AL_EFFECTSLOT_EFFECT, EXTEfx.AL_EFFECT_NULL);
                    EXTEfx.alDeleteAuxiliaryEffectSlots(runtime.slotId());
                    EXTEfx.alDeleteEffects(runtime.effectId());
                    throwIfAlError("closing EFX runtime");
                } catch (Throwable ignored) {
                }
            }
            for (FilterRuntime runtime : filters.values()) {
                try {
                    clearAlError();
                    EXTEfx.alDeleteFilters(runtime.filterId());
                    throwIfAlError("closing EFX filter");
                } catch (Throwable ignored) {
                }
            }
        }
        effects.clear();
        filters.clear();
        activeEffects.clear();
        activeFilters.clear();
        lastMixRevision = Long.MIN_VALUE;

        for (AuralisEffect effect : observedEffects) {
            try {
                effect.close();
            } catch (Throwable failure) {
                GFBsAuralis.LOGGER.warn("Failed to close Auralis effect {}", safeEffectId(effect), failure);
            }
        }
        observedEffects.clear();
    }

    @Override
    public void close() {
        if (!openAL.isAvailable()) return;
        openAL.execute(this::closeOnALThread);
    }

    private void ensureEffect(EfxEffect effect) {
        EfxRuntime runtime = effects.get(effect);
        if (runtime == null) {
            runtime = createEffect(effect);
            if (runtime == null) return;
            effects.put(effect, runtime);
        }

        if (runtime.revision() != effect.getRevision()) {
            try {
                clearAlError();
                applyEffectParameters(runtime.effectId(), effect);
                EXTEfx.alAuxiliaryEffectSlotf(runtime.slotId(), EXTEfx.AL_EFFECTSLOT_GAIN, effect.getWet());
                throwIfAlError("updating effect slot " + effect.getId());
                effects.put(effect, runtime.withRevision(effect.getRevision()));
            } catch (Throwable failure) {
                warnOnce(
                        "effect-update:" + effect.getId(),
                        "Unable to update OpenAL EFX effect '{}'; keeping its last valid state: {}",
                        effect.getId(), String.valueOf(failure.getMessage())
                );
            }
        }
    }

    private EfxRuntime createEffect(EfxEffect effect) {
        int effectId = 0;
        int slotId = 0;
        try {
            clearAlError();
            effectId = EXTEfx.alGenEffects();
            EXTEfx.alEffecti(effectId, EXTEfx.AL_EFFECT_TYPE, effect.getType().alToken());
            applyEffectParameters(effectId, effect);
            slotId = EXTEfx.alGenAuxiliaryEffectSlots();
            EXTEfx.alAuxiliaryEffectSloti(slotId, EXTEfx.AL_EFFECTSLOT_EFFECT, effectId);
            EXTEfx.alAuxiliaryEffectSlotf(slotId, EXTEfx.AL_EFFECTSLOT_GAIN, effect.getWet());
            throwIfAlError("creating " + effect.getType());
            return new EfxRuntime(effectId, slotId, effect.getRevision());
        } catch (Throwable failure) {
            if (slotId != 0) try { EXTEfx.alDeleteAuxiliaryEffectSlots(slotId); } catch (Throwable ignored) { }
            if (effectId != 0) try { EXTEfx.alDeleteEffects(effectId); } catch (Throwable ignored) { }
            clearAlError();
            warnOnce(
                    "effect-create:" + effect.getId(),
                    "OpenAL rejected EFX effect '{}' ({}); it will be bypassed: {}",
                    effect.getId(), effect.getType(), String.valueOf(failure.getMessage())
            );
            return null;
        }
    }

    private void applyEffectParameters(int effectId, EfxEffect effect) {
        for (Map.Entry<EfxParameter, Object> entry : effect.parameters().entrySet()) {
            EfxParameter parameter = entry.getKey();
            Object value = entry.getValue();
            if (parameter.kind() == EfxValueKind.FLOAT) {
                EXTEfx.alEffectf(effectId, parameter.alToken(), ((Number) value).floatValue());
            } else if (parameter.kind() == EfxValueKind.INTEGER) {
                EXTEfx.alEffecti(effectId, parameter.alToken(), ((Number) value).intValue());
            } else {
                float[] vector = (float[]) value;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    FloatBuffer values = stack.floats(vector[0], vector[1], vector[2]);
                    EXTEfx.alEffectfv(effectId, parameter.alToken(), values);
                }
            }
        }
        throwIfAlError("updating " + effect.getId());
    }

    private void ensureFilter(EfxFilter filter) {
        FilterRuntime runtime = filters.get(filter);
        if (runtime == null) {
            runtime = createFilter(filter);
            if (runtime == null) return;
            filters.put(filter, runtime);
        }
        if (runtime.revision() != filter.getRevision()) {
            try {
                clearAlError();
                applyFilterParameters(runtime.filterId(), filter);
                filters.put(filter, runtime.withRevision(filter.getRevision()));
            } catch (Throwable failure) {
                warnOnce(
                        "filter-update:" + filter.getId(),
                        "Unable to update OpenAL EFX filter '{}'; keeping its last valid state: {}",
                        filter.getId(), String.valueOf(failure.getMessage())
                );
            }
        }
    }

    private FilterRuntime createFilter(EfxFilter filter) {
        int filterId = 0;
        try {
            clearAlError();
            filterId = EXTEfx.alGenFilters();
            EXTEfx.alFilteri(filterId, EXTEfx.AL_FILTER_TYPE, filter.getType().alToken());
            applyFilterParameters(filterId, filter);
            throwIfAlError("creating filter " + filter.getType());
            return new FilterRuntime(filterId, filter.getRevision());
        } catch (Throwable failure) {
            if (filterId != 0) try { EXTEfx.alDeleteFilters(filterId); } catch (Throwable ignored) { }
            clearAlError();
            warnOnce(
                    "filter-create:" + filter.getId(),
                    "OpenAL rejected EFX filter '{}' ({}); it will be bypassed: {}",
                    filter.getId(), filter.getType(), String.valueOf(failure.getMessage())
            );
            return null;
        }
    }

    private void applyFilterParameters(int filterId, EfxFilter filter) {
        for (Map.Entry<EfxFilterParameter, Float> entry : filter.parameters().entrySet()) {
            EXTEfx.alFilterf(filterId, entry.getKey().alToken(), entry.getValue());
        }
        throwIfAlError("updating filter " + filter.getId());
    }

    private void releaseUnusedRuntimes(Set<EfxEffect> activeEffects, Set<EfxFilter> activeFilters) {
        Set<Integer> boundSlots = new java.util.HashSet<>();
        Set<Integer> boundFilters = new java.util.HashSet<>();
        for (SourceBinding binding : sourceBindings.values()) {
            boundSlots.addAll(binding.slotIds());
            boundFilters.addAll(binding.filterIds());
        }

        var effectIterator = effects.entrySet().iterator();
        while (effectIterator.hasNext()) {
            Map.Entry<EfxEffect, EfxRuntime> entry = effectIterator.next();
            EfxRuntime runtime = entry.getValue();
            if (activeEffects.contains(entry.getKey()) || boundSlots.contains(runtime.slotId())) continue;
            try {
                clearAlError();
                EXTEfx.alAuxiliaryEffectSloti(
                        runtime.slotId(), EXTEfx.AL_EFFECTSLOT_EFFECT, EXTEfx.AL_EFFECT_NULL
                );
                EXTEfx.alDeleteAuxiliaryEffectSlots(runtime.slotId());
                EXTEfx.alDeleteEffects(runtime.effectId());
                throwIfAlError("releasing inactive EFX runtime");
                effectIterator.remove();
            } catch (Throwable failure) {
                warnOnce(
                        "effect-release:" + entry.getKey().getId(),
                        "Unable to release inactive OpenAL effect '{}': {}",
                        entry.getKey().getId(), String.valueOf(failure.getMessage())
                );
            }
        }

        var filterIterator = filters.entrySet().iterator();
        while (filterIterator.hasNext()) {
            Map.Entry<EfxFilter, FilterRuntime> entry = filterIterator.next();
            FilterRuntime runtime = entry.getValue();
            if (activeFilters.contains(entry.getKey()) || boundFilters.contains(runtime.filterId())) continue;
            try {
                clearAlError();
                EXTEfx.alDeleteFilters(runtime.filterId());
                throwIfAlError("releasing inactive EFX filter");
                filterIterator.remove();
            } catch (Throwable failure) {
                warnOnce(
                        "filter-release:" + entry.getKey().getId(),
                        "Unable to release inactive OpenAL filter '{}': {}",
                        entry.getKey().getId(), String.valueOf(failure.getMessage())
                );
            }
        }
    }

    private void requireAudioThread() {
        if (!openAL.isOnAudioThread()) {
            throw new IllegalStateException("OpenAL effect rack operation called off the Auralis audio thread");
        }
    }

    private static void clearAlError() {
        for (int attempts = 0; attempts < 16; attempts++) {
            if (AL10.alGetError() == AL10.AL_NO_ERROR) return;
        }
    }

    private static void throwIfAlError(String operation) {
        int error = AL10.alGetError();
        if (error != AL10.AL_NO_ERROR) {
            throw new IllegalStateException("OpenAL error 0x" + Integer.toHexString(error) + " while " + operation);
        }
    }

    private void warnOnce(String key, String message, Object... args) {
        if (warned.add(key)) GFBsAuralis.LOGGER.warn(message, args);
    }

    private static String safeEffectId(AuralisEffect effect) {
        try {
            return String.valueOf(effect.getId());
        } catch (Throwable ignored) {
            return effect.getClass().getName();
        }
    }
}
