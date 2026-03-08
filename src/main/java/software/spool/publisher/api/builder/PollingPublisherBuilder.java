package software.spool.publisher.api.builder;

import software.spool.publisher.api.ErrorRouter;
import software.spool.publisher.api.Publisher;
import software.spool.publisher.api.port.EventBusEmitter;
import software.spool.publisher.api.port.InboxReader;
import software.spool.publisher.api.port.InboxUpdater;
import software.spool.publisher.api.strategy.PollingPublisher;
import software.spool.publisher.internal.control.InboxItemHandler;
import software.spool.publisher.internal.decorator.SafeEventBusEmitter;
import software.spool.publisher.internal.decorator.SafeInboxReader;
import software.spool.publisher.internal.decorator.SafeInboxUpdater;
import software.spool.publisher.internal.util.InMemoryEventBus;
import software.spool.publisher.internal.util.InMemoryInbox;

import java.time.Duration;
import java.util.Objects;

public class PollingPublisherBuilder {
    private InboxReader reader;
    private InboxUpdater updater;
    private EventBusEmitter emitter;
    private Duration interval;
    private ErrorRouter errorRouter;

    private PollingPublisherBuilder() {
        InMemoryInbox inbox = new InMemoryInbox();
        this.reader = inbox;
        this.updater = inbox;
        this.emitter = new InMemoryEventBus();
    }

    public static PollingPublisherBuilder create() {
        return new PollingPublisherBuilder();
    }

    public PollingPublisherBuilder from(InboxReader reader) {
        this.reader = SafeInboxReader.of(reader);
        return this;
    }

    public PollingPublisherBuilder with(InboxUpdater updater) {
        this.updater = SafeInboxUpdater.of(updater);
        return this;
    }

    public PollingPublisherBuilder on(EventBusEmitter emitter) {
        this.emitter = SafeEventBusEmitter.of(emitter);
        return this;
    }


    public PollingPublisherBuilder each(Duration interval) {
        this.interval = interval;
        return this;
    }

    public PollingPublisherBuilder withErrorRouter(ErrorRouter errorRouter) {
        this.errorRouter = errorRouter;
        return this;
    }

    public Publisher build() {
        Objects.requireNonNull(reader, "reader cannot be null");
        Objects.requireNonNull(emitter, "emitter cannot be null");
        Objects.requireNonNull(updater, "updater cannot be null");
        Objects.requireNonNull(errorRouter, "errorRouter cannot be null");
        return new Publisher(
                new PollingPublisher(reader, new InboxItemHandler(emitter, updater), interval),
                errorRouter
        );
    }
}
