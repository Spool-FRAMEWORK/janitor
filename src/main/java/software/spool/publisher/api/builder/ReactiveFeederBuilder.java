package software.spool.publisher.api.builder;

import software.spool.core.port.EventBusEmitter;
import software.spool.core.port.EventBusListener;
import software.spool.core.port.InboxUpdater;
import software.spool.core.port.decorator.SafeEventBusEmitter;
import software.spool.core.port.decorator.SafeEventBusListener;
import software.spool.core.port.decorator.SafeInboxUpdater;
import software.spool.core.utils.ErrorRouter;
import software.spool.publisher.api.Feeder;
import software.spool.publisher.api.strategy.ReactiveFeeder;
import software.spool.publisher.internal.control.InboxItemStoredHandler;

import java.util.Objects;

/**
 * Fluent builder that configures and assembles a reactive {@link Feeder}.
 *
 * <p>
 * The resulting feeder listens for {@code InboxItemStored} events on the
 * event bus and publishes each item immediately. All ports are automatically
 * wrapped in their corresponding {@code Safe*} decorators.
 * </p>
 *
 * <pre>{@code
 * Feeder feeder = FeederBuilderFactory.reactive()
 *         .from(eventBusListener)
 *         .with(inboxUpdater)
 *         .on(eventBusEmitter)
 *         .withErrorRouter(errorRouter)
 *         .create();
 * }</pre>
 */
public class ReactiveFeederBuilder {
    private EventBusListener listener;
    private InboxUpdater updater;
    private EventBusEmitter emitter;
    private ErrorRouter errorRouter;

    ReactiveFeederBuilder() {
    }

    /**
     * Sets the event bus listener the feeder subscribes to.
     *
     * @param listener the event bus listener; must not be {@code null}
     * @return this builder for chaining
     */
    public ReactiveFeederBuilder from(EventBusListener listener) {
        this.listener = SafeEventBusListener.of(listener);
        return this;
    }

    /**
     * Sets the inbox updater used to change inbox item statuses.
     *
     * @param updater the inbox updater; must not be {@code null}
     * @return this builder for chaining
     */
    public ReactiveFeederBuilder with(InboxUpdater updater) {
        this.updater = SafeInboxUpdater.of(updater);
        return this;
    }

    /**
     * Sets the event bus emitter for publishing {@code ItemPublished} events.
     *
     * @param emitter the event bus emitter; must not be {@code null}
     * @return this builder for chaining
     */
    public ReactiveFeederBuilder on(EventBusEmitter emitter) {
        this.emitter = SafeEventBusEmitter.of(emitter);
        return this;
    }

    /**
     * Sets the error router for handling exceptions during publishing.
     *
     * @param errorRouter the error router; must not be {@code null}
     * @return this builder for chaining
     */
    public ReactiveFeederBuilder withErrorRouter(ErrorRouter errorRouter) {
        this.errorRouter = errorRouter;
        return this;
    }

    /**
     * Builds and returns the configured reactive {@link Feeder}.
     *
     * @return a new {@code Feeder} ready to start publishing
     * @throws NullPointerException if any required port has not been set
     */
    public Feeder create() {
        Objects.requireNonNull(listener, "listener is required");
        Objects.requireNonNull(updater, "updater is required");
        Objects.requireNonNull(emitter, "emitter is required");
        Objects.requireNonNull(errorRouter, "errorRouter is required");
        return new Feeder(
                new ReactiveFeeder(listener, new InboxItemStoredHandler(updater, emitter, errorRouter)),
                errorRouter);
    }
}
