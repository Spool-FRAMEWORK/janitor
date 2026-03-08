package software.spool.publisher.internal.decorator;

import software.spool.core.control.Handler;
import software.spool.core.exception.EventBusListenException;
import software.spool.core.exception.SpoolException;
import software.spool.core.model.Event;
import software.spool.publisher.api.port.EventBusListener;
import software.spool.publisher.internal.port.Subscription;

public class SafeEventBusListener implements EventBusListener {
    private final EventBusListener listener;

    public SafeEventBusListener(EventBusListener listener) {
        this.listener = listener;
    }

    @Override
    public <E extends Event> Subscription on(Class<E> event, Handler<E> handler) {
        try {
            return listener.on(event, handler);
        } catch (SpoolException e) { throw e; }
        catch (Exception e) {
            throw new EventBusListenException(event, e.getMessage(), e);
        }
    }

    public static SafeEventBusListener of(EventBusListener listener) {
        return new SafeEventBusListener(listener);
    }
}
