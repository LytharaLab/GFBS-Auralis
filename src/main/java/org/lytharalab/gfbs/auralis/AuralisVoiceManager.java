package org.lytharalab.gfbs.auralis;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Selects which logical Auralis voices own scarce OpenAL sources.
 *
 * <p>Logical voices always advance independently. Only voices that are currently
 * audible enough and rank within the physical source budget are materialized.</p>
 */
final class AuralisVoiceManager {
    private record Candidate(
            AuralisSoundInstanceImpl voice,
            double score,
            boolean alreadyPhysical,
            long initialBufferedStartToken
    ) {}

    private final OpenALSourcePool sourcePool;
    private final float materializeGainThreshold;
    private final float virtualizeGainThreshold;

    private volatile int logicalVoiceCount;
    private volatile int playingVoiceCount;
    private volatile int physicalVoiceCount;
    private volatile int virtualVoiceCount;

    AuralisVoiceManager(
            OpenALSourcePool sourcePool,
            float materializeGainThreshold,
            float virtualizeGainThreshold
    ) {
        this.sourcePool = sourcePool;
        this.materializeGainThreshold = Math.max(0.0f, materializeGainThreshold);
        this.virtualizeGainThreshold = Math.max(
                0.0f,
                Math.min(this.materializeGainThreshold, virtualizeGainThreshold)
        );
    }

    void tick(
            Collection<AuralisSoundInstanceImpl> voices,
            Vec3 listenerPos,
            float attenuationExponent,
            long nowNanos
    ) {
        List<Candidate> candidates = new ArrayList<>(Math.min(voices.size(), 1024));
        Map<AuralisSoundInstanceImpl, Long> deferredInitialStarts = new IdentityHashMap<>();

        // Phase 1: advance established logical voices. A newly-started buffered
        // voice gets one deferred pass so an immediately available physical
        // Source can start at sample zero instead of seeking past scheduler delay.
        for (AuralisSoundInstanceImpl voice : voices) {
            if (voice == null || voice.isDisposed()) continue;
            long initialBufferedStartToken = voice.claimInitialBufferedStartForScheduling();
            if (initialBufferedStartToken == InitialBufferedPlaybackGuard.NONE) {
                voice.advanceLogicalVoice(nowNanos);
            } else {
                deferredInitialStarts.put(voice, initialBufferedStartToken);
            }

            if (!voice.isPlaying()) {
                if (voice.isPhysicalVoice()) {
                    voice.virtualizePhysicalVoice();
                }
                continue;
            }

            if (!voice.isBindingRequested()) {
                if (voice.isPhysicalVoice()) {
                    voice.virtualizePhysicalVoice();
                }
                continue;
            }

            float audibleGain = voice.estimateAudibleGain(listenerPos, attenuationExponent);
            float threshold = voice.isPhysicalVoice()
                    ? virtualizeGainThreshold
                    : materializeGainThreshold;

            if (audibleGain <= threshold) {
                if (voice.isPhysicalVoice()) {
                    voice.virtualizePhysicalVoice();
                }
                continue;
            }

            candidates.add(new Candidate(
                    voice,
                    voice.voiceScore(listenerPos, attenuationExponent),
                    voice.isPhysicalVoice(),
                    initialBufferedStartToken
            ));
        }

        // Stable preference for an already-materialized voice on exact ties avoids
        // source churn when many identical emitters have the same score.
        candidates.sort(
                Comparator.<Candidate>comparingDouble(Candidate::score).reversed()
                        .thenComparing(Candidate::alreadyPhysical, Comparator.reverseOrder())
                        .thenComparingInt(c -> System.identityHashCode(c.voice()))
        );

        int budget = Math.max(1, sourcePool.getEffectiveMaxSources());
        int selectedCount = Math.min(budget, candidates.size());
        Set<AuralisSoundInstanceImpl> selected = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < selectedCount; i++) {
            selected.add(candidates.get(i).voice());
        }

        // Phase 2: release losers first, making their Sources immediately available
        // to higher-ranked virtual voices in this same tick.
        for (Candidate candidate : candidates) {
            AuralisSoundInstanceImpl voice = candidate.voice();
            if (!selected.contains(voice) && voice.isPhysicalVoice()) {
                voice.onEvicted();
            }
        }

        // Phase 3: materialize selected virtual voices. A first-pass buffered
        // voice starts at zero and rebases its logical clock atomically with
        // alSourcePlay; all later materializations retain cursor-based resume.
        Set<AuralisSoundInstanceImpl> committedInitialStarts =
                java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < selectedCount; i++) {
            Candidate candidate = candidates.get(i);
            AuralisSoundInstanceImpl voice = candidate.voice();
            if (!voice.isPhysicalVoice()) {
                AuralisSoundInstanceImpl.PhysicalMaterializationResult result = voice.materializePhysicalVoice(
                        listenerPos,
                        attenuationExponent,
                        candidate.initialBufferedStartToken()
                );
                if (result.committedInitialBufferedStart()) {
                    committedInitialStarts.add(voice);
                }
            }
        }

        // If the first pass stayed virtual (inaudible, over budget, stopped, or
        // failed to allocate), preserve the original virtualization contract by
        // accounting for all elapsed logical time immediately.
        for (AuralisSoundInstanceImpl voice : deferredInitialStarts.keySet()) {
            if (!committedInitialStarts.contains(voice)) {
                voice.advanceLogicalVoice(nowNanos);
            }
        }

        int logical = 0;
        int playing = 0;
        int physical = 0;
        for (AuralisSoundInstanceImpl voice : voices) {
            if (voice == null || voice.isDisposed()) continue;
            logical++;
            if (voice.isPlaying()) playing++;
            if (voice.isPhysicalVoice()) physical++;
        }

        logicalVoiceCount = logical;
        playingVoiceCount = playing;
        physicalVoiceCount = physical;
        virtualVoiceCount = Math.max(0, playing - physical);
    }

    int getLogicalVoiceCount() {
        return logicalVoiceCount;
    }

    int getPlayingVoiceCount() {
        return playingVoiceCount;
    }

    int getPhysicalVoiceCount() {
        return physicalVoiceCount;
    }

    int getVirtualVoiceCount() {
        return virtualVoiceCount;
    }
}
