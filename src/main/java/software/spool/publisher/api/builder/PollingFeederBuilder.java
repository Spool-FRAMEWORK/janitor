package software.spool.publisher.api.builder;

import software.spool.core.adapter.*;
import software.spool.publisher.api.Feeder;
import software.spool.core.port.*;
import software.spool.publisher.api.strategy.PollingFeeder;
import software.spool.core.port.decorator.*;
import software.spool.core.utils.*;
import software.spool.publisher.internal.control.InboxItemStoredHandler;

import java.time.Duration;
import java.util.Objects;

public class PollingFeederBuilder {
    private InboxReader reader;
    private InboxUpdater updater;
    private EventBusEmitter emitter;
    private Duration interval;
    private ErrorRouter errorRouter;

    private PollingFeederBuilder() {
        InMemoryInbox inbox = new InMemoryInbox();
        this.reader = inbox;
        this.updater = inbox;
        this.emitter = new InMemoryEventBus();
    }

    public static PollingFeederBuilder create() {
        return new PollingFeederBuilder();
    }

    public PollingFeederBuilder from(InboxReader reader) {
        this.reader = SafeInboxReader.of(reader);
        return this;
    }

    public PollingFeederBuilder with(InboxUpdater updater) {
        this.updater = SafeInboxUpdater.of(updater);
        return this;
    }

    public PollingFeederBuilder on(EventBusEmitter emitter) {
        this.emitter = SafeEventBusEmitter.of(emitter);
        return this;
    }


    public PollingFeederBuilder each(Duration interval) {
        this.interval = interval;
        return this;
    }

    public PollingFeederBuilder withErrorRouter(ErrorRouter errorRouter) {
        this.errorRouter = errorRouter;
        return this;
    }

    public Feeder build() {
        Objects.requireNonNull(reader, "reader cannot be null");
        Objects.requireNonNull(emitter, "emitter cannot be null");
        Objects.requireNonNull(updater, "updater cannot be null");
        Objects.requireNonNull(errorRouter, "errorRouter cannot be null");
        return new Feeder(
                new PollingFeeder(reader, new InboxItemStoredHandler(updater, emitter), interval),
                errorRouter
        );
    }
}
