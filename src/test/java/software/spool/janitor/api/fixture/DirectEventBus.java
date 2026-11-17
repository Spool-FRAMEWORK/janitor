package software.spool.janitor.api.fixture;

import software.spool.core.model.Event;
import software.spool.core.port.bus.EventSubscriber;
import software.spool.core.port.bus.Handler;
import software.spool.core.port.bus.Subscription;

import java.util.HashMap;
import java.util.Map;

/** Hands each emitted event to its subscriber in the thread that emits it. */
public final class DirectEventBus implements EventSubscriber {
    private final Map<Class<?>, Handler<?>> handlers = new HashMap<>();

    @Override
    public <E extends Event> Subscription subscribe(Class<E> eventType, Handler<E> handler) {
        handlers.put(eventType, handler);
        return Subscription.NULL;
    }

    @SuppressWarnings("unchecked")
    public <E extends Event> void emit(E event) {
        ((Handler<E>) handlers.get(event.getClass())).handle(event);
    }
}
