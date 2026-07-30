package org.lytharalab.gfbs.auralis.api.event;

import org.lytharalab.gfbs.auralis.api.AuralisSoundInstance;
import net.minecraft.sounds.SoundEvent;

/**
 * 当一个新的声音实例被创建时触发
 */
public record SoundCreatedEvent(AuralisSoundInstance instance, SoundEvent soundEvent) implements AuralisEvent {
}
