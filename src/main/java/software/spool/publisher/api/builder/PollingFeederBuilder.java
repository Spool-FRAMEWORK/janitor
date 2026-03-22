package software.spool.publisher.api.builder;

import software.spool.core.port.EventBusEmitter;
import software.spool.core.port.InboxUpdater;
import software.spool.core.port.decorator.SafeEventBusEmitter;
import software.spool.core.port.decorator.SafeInboxUpdater;
import software.spool.core.utils.ErrorRouter;
import software.spool.publisher.api.Feeder;
import software.spool.publisher.api.port.InboxReader;
import software.spool.publisher.api.strategy.PollingFeeder;
import software.spool.publisher.internal.port.decorator.SafeInboxReader;
import software.spool.publisher.internal.control.InboxItemStoredHandler;

import java.time.Duration;
import java.util.Objects;

/**
 * Fluent builder that configures and assembles a polling-based {@link Feeder}.
 *
 * <p>
 * The resulting feeder periodically queries the inbox for items with
 * {@code PUBLISHING} status and processes them. All ports are automatically
 * wrapped in their corresponding {@code Safe*} decorators.
 * </p>
 *
 * <pre>{@code
 * Feeder feeder = FeederBuilderFactory.polling()
 *         .from(inboxReader)
 *         .with(inboxUpdater)
 *         .on(eventBusEmitter)
 *         .each(Duration.ofSeconds(15))
 *         .withErrorRouter(errorRouter)
 *         .create();
 * }</pre>
 */
public class PollingFeederBuilder {
    private InboxReader reader;
    private InboxUpdater updater;
    private EventBusEmitter emitter;
    private Duration interval;
    private ErrorRouter errorRouter;

    PollingFeederBuilder() {
    }

    /**
     * Sets the inbox reader for querying items by status.
     *
     * @param reader the inbox reader; must not be {@code null}
     * @return this builder for chaining
     */
    public PollingFeederBuilder from(InboxReader reader) {
        this.reader = SafeInboxReader.of(reader);
        return this;
    }

    /**
     * Sets the inbox updater used to change inbox item statuses.
     *
     * @param updater the inbox updater; must not be {@code null}
     * @return this builder for chaining
     */
    public PollingFeederBuilder with(InboxUpdater updater) {
        this.updater = SafeInboxUpdater.of(updater);
        return this;
    }

    /**
     * Sets the event bus emitter for publishing {@code ItemPublished} events.
     *
     * @param emitter the event bus emitter; must not be {@code null}
     * @return this builder for chaining
     */
    public PollingFeederBuilder on(EventBusEmitter emitter) {
        this.emitter = SafeEventBusEmitter.of(emitter);
        return this;
    }

    /**
     * Sets the polling interval.
     *
     * @param interval the interval between polls; defaults to 30 seconds if not set
     * @return this builder for chaining
     */
    public PollingFeederBuilder each(Duration interval) {
        this.interval = interval;
        return this;
    }

    /**
     * Sets the error router for handling exceptions during publishing.
     *
     * @param errorRouter the error router; must not be {@code null}
     * @return this builder for chaining
     */
    public PollingFeederBuilder withErrorRouter(ErrorRouter errorRouter) {
        this.errorRouter = errorRouter;
        return this;
    }

    /**
     * Builds and returns the configured polling {@link Feeder}.
     *
     * @return a new {@code Feeder} ready to start publishing
     * @throws NullPointerException if any required port has not been set
     */
    public Feeder create() {
        Objects.requireNonNull(reader, "reader cannot be null");
        Objects.requireNonNull(emitter, "emitter cannot be null");
        Objects.requireNonNull(updater, "updater cannot be null");
        Objects.requireNonNull(errorRouter, "errorRouter cannot be null");
        return new Feeder(
                new PollingFeeder(reader, new InboxItemStoredHandler(updater, emitter, errorRouter), interval),
                errorRouter);
    }
}
