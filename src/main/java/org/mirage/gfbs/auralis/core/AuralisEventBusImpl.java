package org.mirage.gfbs.auralis.core;

import org.mirage.gfbs.auralis.api.event.AuralisEvent;
import org.mirage.gfbs.auralis.api.event.AuralisEventBus;

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
        List<Consumer<? extends AuralisEvent>> list = listeners.get(event.getClass());
        if (list != null) {
            for (Consumer<? extends AuralisEvent> handler : list) {
                try {
                    ((Consumer<AuralisEvent>) handler).accept(event);
                } catch (Exception e) {
                    System.err.println("[Auralis] Error handling event " + event.getClass().getSimpleName());
                    e.printStackTrace();
                }
            }
        }
    }
}
