package org.lytharalab.gfbs.auralis.api.effect;

import org.lytharalab.gfbs.auralis.api.processing.AudioProcessor;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Convenience PCM effect backed by a per-voice processor factory. */
public final class ProcessorEffect extends AbstractAuralisEffect implements PcmEffect {
    private final Function<Float, ? extends AudioProcessor> factory;

    public ProcessorEffect(String id, Supplier<? extends AudioProcessor> factory) {
        super(id);
        Supplier<? extends AudioProcessor> checked = Objects.requireNonNull(factory, "factory");
        this.factory = wet -> checked.get();
    }

    /** Creates processors with the effect's current wet value. */
    public ProcessorEffect(String id, Function<Float, ? extends AudioProcessor> factory) {
        super(id);
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public AudioProcessor createProcessor() {
        return createProcessor(getWet());
    }

    @Override
    public AudioProcessor createProcessor(float wet) {
        return Objects.requireNonNull(factory.apply(wet), "PCM effect factory returned null: " + getId());
    }

    @Override
    public ProcessorEffect setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        return this;
    }

    @Override
    public ProcessorEffect setWet(float wet) {
        super.setWet(wet);
        return this;
    }
}
