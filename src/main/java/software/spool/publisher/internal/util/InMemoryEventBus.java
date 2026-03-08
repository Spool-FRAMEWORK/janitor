package software.spool.publisher.internal.util;

import software.spool.core.control.Handler;
import software.spool.core.exception.EventBusEmitException;
import software.spool.core.exception.EventBusListenException;
import software.spool.core.model.Event;
import software.spool.publisher.api.port.EventBusEmitter;
import software.spool.publisher.api.port.EventBusListener;
import software.spool.publisher.internal.port.Subscription;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemoryEventBus implements EventBusEmitter, EventBusListener {
    private final ConcurrentHashMap<Class<?>, CopyOnWriteArrayList<Handler<?>>> registry =
            new ConcurrentHashMap<>();

    @Override
    public <E extends Event> Subscription on(Class<E> event, Handler<E> handler) throws EventBusListenException {
        registry.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>())
                .add(handler);

        return new InMemorySubscription(
                () -> registry.getOrDefault(event, new CopyOnWriteArrayList<>()).remove(handler)
        );
    }

    @Override
    @SuppressWarnings("unchecked")
    public void emit(Event event) throws EventBusEmitException {
        try {
            List<Handler<?>> handlers = registry.getOrDefault(
                    event.getClass(),
                    new CopyOnWriteArrayList<>()
            );
            for (Handler<?> handler : handlers)
                ((Handler<Event>) handler).handle(event);
        } catch (Exception e) {
            throw new EventBusEmitException(event, e.getMessage(), e);
        }
    }
}
