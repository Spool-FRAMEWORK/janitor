package software.spool.publisher.api.strategy;

import software.spool.core.control.Handler;
import software.spool.core.model.InboxItem;
import software.spool.core.model.InboxItemStatus;
import software.spool.core.model.InboxItemStored;
import software.spool.core.port.Subscription;
import software.spool.publisher.api.port.InboxReader;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * Polling-based {@link FeederStrategy} that periodically reads inbox items
 * with {@code PUBLISHING} status and processes them.
 *
 * <p>
 * When started, this strategy creates a single-threaded scheduled executor
 * that queries the inbox at a fixed interval (default: 30 seconds). Each
 * matching inbox item is converted to an {@link InboxItemStored} event and
 * delegated to the handler.
 * </p>
 *
 * <p>
 * This strategy is best suited for batch-oriented or high-throughput scenarios
 * where periodic polling is more efficient than event-driven processing.
 * </p>
 */
public class PollingFeeder implements FeederStrategy {
    private final InboxReader reader;
    private final Handler<InboxItemStored> handler;
    private final Duration interval;

    /**
     * Creates a new polling feeder.
     *
     * @param reader   the inbox reader for querying items by status
     * @param handler  the handler that processes each inbox item
     * @param interval the polling interval; defaults to 30 seconds if {@code null}
     */
    public PollingFeeder(InboxReader reader, Handler<InboxItemStored> handler, Duration interval) {
        this.reader = reader;
        this.handler = handler;
        this.interval = Objects.requireNonNullElse(interval, Duration.ofSeconds(30));
    }

    /**
     * Starts a scheduled executor that polls the inbox at the configured interval.
     *
     * @return a {@link Subscription} whose {@code cancel()} shuts down the
     *         scheduler
     */
    @Override
    public Subscription start() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(
                () -> reader.findByStatus(InboxItemStatus.UNPUBLISHED).map(this::toEvent).forEach(handler::handle),
                0, interval.toMillis(), TimeUnit.MILLISECONDS);
        return new Subscription() {
            public void cancel() {
                scheduler.shutdown();
            }

            public boolean isActive() {
                return !scheduler.isShutdown();
            }
        };
    }

    private InboxItemStored toEvent(InboxItem inboxItem) {
        return InboxItemStored.builder()
                .idempotencyKey(inboxItem.idempotencyKey())
                .build();
    }
}
