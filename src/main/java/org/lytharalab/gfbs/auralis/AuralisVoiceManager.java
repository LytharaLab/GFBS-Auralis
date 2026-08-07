package org.lytharalab.gfbs.auralis;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Selects which logical Auralis voices own scarce OpenAL sources.
 *
 * <p>Logical voices always advance independently. Only voices that are currently
 * audible enough and rank within the physical source budget are materialized.</p>
 */
final class AuralisVoiceManager {
    private record Candidate(AuralisSoundInstanceImpl voice, double score, boolean alreadyPhysical) {}

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

        int logical = 0;
        int playing = 0;

        // Phase 1: advance every logical voice, including fully virtual voices.
        for (AuralisSoundInstanceImpl voice : voices) {
            if (voice == null || voice.isDisposed()) continue;
            logical++;
            voice.advanceLogicalVoice(nowNanos);

            if (!voice.isPlaying()) {
                if (voice.isPhysicalVoice()) {
                    voice.virtualizePhysicalVoice();
                }
                continue;
            }
            playing++;

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
                    voice.isPhysicalVoice()
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

        // Phase 3: materialize selected virtual voices at their current logical cursor.
        for (int i = 0; i < selectedCount; i++) {
            AuralisSoundInstanceImpl voice = candidates.get(i).voice();
            if (!voice.isPhysicalVoice()) {
                voice.materializePhysicalVoice(listenerPos, attenuationExponent);
            }
        }

        int physical = 0;
        for (AuralisSoundInstanceImpl voice : voices) {
            if (voice != null && !voice.isDisposed() && voice.isPhysicalVoice()) {
                physical++;
            }
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
