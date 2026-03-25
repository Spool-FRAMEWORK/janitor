package software.spool.feeder.api.strategy;

import software.spool.core.control.Handler;
import software.spool.core.model.InboxItem;
import software.spool.core.model.InboxItemStatus;
import software.spool.core.model.InboxItemStored;
import software.spool.core.port.PollingScheduler;
import software.spool.core.utils.CancellationToken;
import software.spool.core.utils.PollingPolicy;
import software.spool.feeder.api.port.InboxReader;

import java.util.Objects;

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
    private final PollingScheduler scheduler;
    private final PollingPolicy policy;

    /**
     * Creates a new polling feeder.
     *
     * @param reader   the inbox reader for querying items by status
     * @param handler  the handler that processes each inbox item
     */
    public PollingFeeder(InboxReader reader, Handler<InboxItemStored> handler, PollingScheduler scheduler, PollingPolicy policy) {
        this.reader = Objects.requireNonNull(reader);
        this.handler = Objects.requireNonNull(handler);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.policy = Objects.requireNonNull(policy);
    }

    /**
     * Starts a scheduled executor that polls the inbox at the configured interval.
     *         scheduler
     */
    @Override
    public void execute(CancellationToken token) {
        scheduler.schedule(
                () -> reader.findByStatus(InboxItemStatus.UNPUBLISHED).map(this::toEvent).forEach(handler::handle),
                policy,
                token
        );
    }

    private InboxItemStored toEvent(InboxItem inboxItem) {
        return InboxItemStored.builder()
                .idempotencyKey(inboxItem.idempotencyKey())
                .build();
    }
}
