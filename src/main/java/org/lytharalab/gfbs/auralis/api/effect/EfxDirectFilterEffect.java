package org.lytharalab.gfbs.auralis.api.effect;

import java.util.Objects;

/** Applies an EFX filter to a source's dry/direct path. */
public final class EfxDirectFilterEffect extends AbstractAuralisEffect {
    private final EfxFilter filter;

    public EfxDirectFilterEffect(String id, EfxFilter filter) {
        super(id);
        this.filter = Objects.requireNonNull(filter, "filter");
    }

    @Override
    public EffectBackend getBackend() {
        return EffectBackend.OPENAL_EFX;
    }

    public EfxFilter getFilter() {
        return filter;
    }

    @Override
    public EfxDirectFilterEffect setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        return this;
    }

    @Override
    public EfxDirectFilterEffect setWet(float wet) {
        super.setWet(wet);
        return this;
    }
}
