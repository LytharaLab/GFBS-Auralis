package org.lytharalab.gfbs.auralis.core;

import org.lytharalab.gfbs.auralis.GFBsAuralis;
import org.lytharalab.gfbs.auralis.api.event.AuralisEvent;
import org.lytharalab.gfbs.auralis.api.event.AuralisEventBus;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class AuralisEventBusImpl implements AuralisEventBus {
    private final Map<Class<? extends AuralisEvent>, List<Consumer<? extends AuralisEvent>>> listeners = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T extends AuralisEvent> void register(Class<T> eventClass, Consumer<T> handler) {
        java.util.Objects.requireNonNull(eventClass, "eventClass");
        java.util.Objects.requireNonNull(handler, "handler");
        listeners.computeIfAbsent(eventClass, k -> new CopyOnWriteArrayList<>())
                .add((Consumer<AuralisEvent>) handler);
    }

    @Override
    public <T extends AuralisEvent> void unregister(Class<T> eventClass, Consumer<T> handler) {
        List<Consumer<? extends AuralisEvent>> list = listeners.get(eventClass);
        if (list != null) {
            list.remove(handler);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void post(AuralisEvent event) {
        java.util.Objects.requireNonNull(event, "event");
        for (Map.Entry<Class<? extends AuralisEvent>, List<Consumer<? extends AuralisEvent>>> entry : listeners.entrySet()) {
            if (!entry.getKey().isAssignableFrom(event.getClass())) continue;
            for (Consumer<? extends AuralisEvent> handler : entry.getValue()) {
                try {
                    ((Consumer<AuralisEvent>) handler).accept(event);
                } catch (Throwable failure) {
                    if (failure instanceof VirtualMachineError fatal) throw fatal;
                    if (failure instanceof ThreadDeath fatal) throw fatal;
                    GFBsAuralis.LOGGER.error(
                            "Auralis event handler failed for {} and was isolated",
                            event.getClass().getSimpleName(),
                            failure
                    );
                }
            }
        }
    }

    public void clear() {
        listeners.clear();
    }
}
