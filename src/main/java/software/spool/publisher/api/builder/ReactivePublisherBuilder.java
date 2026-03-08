package software.spool.publisher.api.builder;

import software.spool.publisher.api.ErrorRouter;
import software.spool.publisher.api.Publisher;
import software.spool.publisher.api.port.EventBusEmitter;
import software.spool.publisher.api.port.EventBusListener;
import software.spool.publisher.api.port.InboxUpdater;
import software.spool.publisher.api.strategy.ReactivePublisher;
import software.spool.publisher.internal.control.InboxItemStoredHandler;
import software.spool.publisher.internal.decorator.SafeEventBusEmitter;
import software.spool.publisher.internal.decorator.SafeEventBusListener;
import software.spool.publisher.internal.decorator.SafeInboxUpdater;
import software.spool.publisher.internal.util.InMemoryEventBus;
import software.spool.publisher.internal.util.InMemoryInbox;

import java.util.Objects;

public class ReactivePublisherBuilder {
    private EventBusListener listener;
    private InboxUpdater updater;
    private EventBusEmitter emitter;
    private ErrorRouter errorRouter;

    private ReactivePublisherBuilder() {
        InMemoryEventBus bus = new InMemoryEventBus();
        this.listener = bus;
        this.updater = new InMemoryInbox();
        this.emitter = bus;
    }

    public static ReactivePublisherBuilder create() {
        return new ReactivePublisherBuilder();
    }

    public ReactivePublisherBuilder from(EventBusListener listener) {
        this.listener = SafeEventBusListener.of(listener);
        return this;
    }

    public ReactivePublisherBuilder with(InboxUpdater updater) {
        this.updater = SafeInboxUpdater.of(updater);
        return this;
    }

    public ReactivePublisherBuilder on(EventBusEmitter emitter) {
        this.emitter = SafeEventBusEmitter.of(emitter);
        return this;
    }

    public ReactivePublisherBuilder withErrorRouter(ErrorRouter errorRouter) {
        this.errorRouter = errorRouter;
        return this;
    }

    public Publisher build() {
        Objects.requireNonNull(listener, "listener is required");
        Objects.requireNonNull(updater, "updater is required");
        Objects.requireNonNull(emitter, "emitter is required");
        Objects.requireNonNull(errorRouter, "errorRouter is required");
        return new Publisher(
                new ReactivePublisher(listener, new InboxItemStoredHandler(updater, emitter)),
                errorRouter
        );
    }
}
