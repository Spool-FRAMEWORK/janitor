package software.spool.publisher.api.builder;

import software.spool.core.adapter.*;
import software.spool.core.port.*;
import software.spool.core.port.decorator.*;
import software.spool.core.utils.*;
import software.spool.publisher.api.Feeder;
import software.spool.publisher.api.strategy.ReactiveFeeder;
import software.spool.publisher.internal.control.InboxItemStoredHandler;

import java.util.Objects;

public class ReactiveFeederBuilder {
    private EventBusListener listener;
    private InboxUpdater updater;
    private EventBusEmitter emitter;
    private ErrorRouter errorRouter;

    private ReactiveFeederBuilder() {
        InMemoryEventBus bus = new InMemoryEventBus();
        this.listener = bus;
        this.updater = new InMemoryInbox();
        this.emitter = bus;
    }

    public static ReactiveFeederBuilder create() {
        return new ReactiveFeederBuilder();
    }

    public ReactiveFeederBuilder from(EventBusListener listener) {
        this.listener = SafeEventBusListener.of(listener);
        return this;
    }

    public ReactiveFeederBuilder with(InboxUpdater updater) {
        this.updater = SafeInboxUpdater.of(updater);
        return this;
    }

    public ReactiveFeederBuilder on(EventBusEmitter emitter) {
        this.emitter = SafeEventBusEmitter.of(emitter);
        return this;
    }

    public ReactiveFeederBuilder withErrorRouter(ErrorRouter errorRouter) {
        this.errorRouter = errorRouter;
        return this;
    }

    public Feeder build() {
        Objects.requireNonNull(listener, "listener is required");
        Objects.requireNonNull(updater, "updater is required");
        Objects.requireNonNull(emitter, "emitter is required");
        Objects.requireNonNull(errorRouter, "errorRouter is required");
        return new Feeder(
                new ReactiveFeeder(listener, new InboxItemStoredHandler(updater, emitter)),
                errorRouter
        );
    }
}
