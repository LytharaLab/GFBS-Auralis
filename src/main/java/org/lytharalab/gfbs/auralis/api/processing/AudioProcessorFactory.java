package org.lytharalab.gfbs.auralis.api.processing;

/** Creates a dedicated PCM processor for one logical sound voice. */
@FunctionalInterface
public interface AudioProcessorFactory {
    AudioProcessor create();
}
