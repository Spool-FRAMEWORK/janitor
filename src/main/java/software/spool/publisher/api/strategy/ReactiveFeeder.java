package software.spool.publisher.api.strategy;

import software.spool.core.control.Handler;
import software.spool.core.model.InboxItemStored;
import software.spool.core.port.*;

public class ReactiveFeeder implements FeederStrategy {
    private final EventBusListener eventBusListener;
    private final Handler<InboxItemStored> handler;

    public ReactiveFeeder(EventBusListener eventBusListener, Handler<InboxItemStored> handler) {
        this.eventBusListener = eventBusListener;
        this.handler = handler;
    }

    @Override
    public Subscription start() {
        return eventBusListener.on(InboxItemStored.class, handler);
    }
}
