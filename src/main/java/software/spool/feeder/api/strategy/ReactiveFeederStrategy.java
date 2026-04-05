package software.spool.feeder.api.strategy;

import software.spool.core.model.event.InboxItemStored;
import software.spool.core.port.bus.EventBusListener;
import software.spool.core.port.bus.Handler;
import software.spool.core.utils.polling.CancellationToken;

import java.util.Objects;

/**
 * Reactive {@link FeederStrategy} that listens for {@link InboxItemStored}
 * events on the event bus and processes each one immediately.
 *
 * <p>
 * When started, this strategy subscribes to {@code InboxItemStored} events.
 * Each event triggers the handler which updates the inbox status to
 * {@code PUBLISHING}, emits an {@code ItemPublished} event, and marks the
 * item as {@code PUBLISHED}.
 * </p>
 *
 * <p>
 * This strategy is best suited for low-latency, event-driven architectures
 * where items should be published as soon as they arrive in the inbox.
 * </p>
 */
public class ReactiveFeederStrategy implements FeederStrategy {
    private final EventBusListener eventBusListener;
    private final Handler<InboxItemStored> handler;

    /**
     * Creates a new reactive feeder.
     *
     * @param eventBusListener the event bus listener to subscribe with
     * @param handler          the handler that processes each inbox item
     */
    public ReactiveFeederStrategy(EventBusListener eventBusListener, Handler<InboxItemStored> handler) {
        this.eventBusListener = Objects.requireNonNull(eventBusListener);
        this.handler = Objects.requireNonNull(handler);
    }

    /**
     * Subscribes to {@link InboxItemStored} events on the event bus.
     *
     */
    @Override
    public void execute(CancellationToken token) {
        eventBusListener.on(InboxItemStored.class, e -> {
            if (token.isCancelled()) return;
            handler.handle(e);
        });
    }
}
