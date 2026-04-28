package software.spool.feeder.api.builder;

import software.spool.core.port.bus.EventPublisher;
import software.spool.core.port.bus.EventSubscriber;
import software.spool.core.port.decorator.SafeEventPublisher;
import software.spool.core.port.decorator.SafeEventSubscriber;
import software.spool.core.port.decorator.SafeInboxUpdater;
import software.spool.core.port.inbox.InboxUpdater;
import software.spool.core.port.watchdog.ModuleHeartBeat;
import software.spool.core.utils.routing.ErrorRouter;
import software.spool.feeder.api.Feeder;
import software.spool.feeder.api.strategy.ReactiveFeederStrategy;
import software.spool.feeder.api.utils.FeederErrorRouter;
import software.spool.feeder.internal.control.EnvelopeStoredHandler;

import java.util.Objects;

public class ReactiveFeederBuilder {
    private final ModuleHeartBeat heartbeat;
    private EventSubscriber listener;
    private InboxUpdater updater;
    private EventPublisher emitter;
    private ErrorRouter errorRouter;

    ReactiveFeederBuilder(ModuleHeartBeat heartbeat) {
        this.heartbeat = heartbeat;
    }

    public ReactiveFeederBuilder from(EventSubscriber listener) {
        this.listener = SafeEventSubscriber.of(listener);
        return this;
    }

    public ReactiveFeederBuilder with(InboxUpdater updater) {
        this.updater = SafeInboxUpdater.of(updater);
        return this;
    }

    public ReactiveFeederBuilder on(EventPublisher emitter) {
        this.emitter = SafeEventPublisher.of(emitter);
        return this;
    }

    public ReactiveFeederBuilder withErrorRouter(ErrorRouter errorRouter) {
        this.errorRouter = errorRouter;
        return this;
    }

    public Feeder create() {
        return new Feeder(initializeStrategy(), getErrorRouter(), heartbeat);
    }

    private ErrorRouter getErrorRouter() {
        return Objects.isNull(errorRouter) ? FeederErrorRouter.defaults(emitter) : errorRouter;
    }

    private EnvelopeStoredHandler initializeHandler() {
        return new EnvelopeStoredHandler(updater, emitter, getErrorRouter());
    }

    private ReactiveFeederStrategy initializeStrategy() {
        return new ReactiveFeederStrategy(listener, initializeHandler());
    }
}
