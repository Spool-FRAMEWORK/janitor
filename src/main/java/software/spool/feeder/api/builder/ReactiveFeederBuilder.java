package software.spool.feeder.api.builder;

import software.spool.core.port.bus.EventBusEmitter;
import software.spool.core.port.bus.EventBusListener;
import software.spool.core.port.decorator.SafeEventBusEmitter;
import software.spool.core.port.decorator.SafeEventBusListener;
import software.spool.core.port.decorator.SafeInboxUpdater;
import software.spool.core.port.inbox.InboxUpdater;
import software.spool.core.port.watchdog.ModuleHeartBeat;
import software.spool.core.utils.routing.ErrorRouter;
import software.spool.feeder.api.Feeder;
import software.spool.feeder.api.strategy.ReactiveFeederStrategy;
import software.spool.feeder.internal.control.InboxItemStoredHandler;

public class ReactiveFeederBuilder {
    private final ModuleHeartBeat heartbeat;
    private EventBusListener listener;
    private InboxUpdater updater;
    private EventBusEmitter emitter;
    private ErrorRouter errorRouter;

    ReactiveFeederBuilder(ModuleHeartBeat heartbeat) {
        this.heartbeat = heartbeat;
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

    public Feeder create() {
        return new Feeder(initializeStrategy(), errorRouter, heartbeat);
    }

    private ReactiveFeederStrategy initializeStrategy() {
        return new ReactiveFeederStrategy(listener, initializeHandler());
    }

    private InboxItemStoredHandler initializeHandler() {
        return new InboxItemStoredHandler(updater, emitter, errorRouter);
    }
}
