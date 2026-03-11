package software.spool.publisher.api.strategy;

import software.spool.core.control.Handler;
import software.spool.core.model.InboxItemStatus;
import software.spool.core.model.InboxItem;
import software.spool.core.model.InboxItemStored;
import software.spool.core.port.*;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.*;

public class PollingFeeder implements FeederStrategy {
    private final InboxReader reader;
    private final Handler<InboxItemStored> handler;
    private final Duration interval;

    public PollingFeeder(InboxReader reader, Handler<InboxItemStored> handler, Duration interval) {
        this.reader = reader;
        this.handler = handler;
        this.interval = Objects.requireNonNullElse(interval, Duration.ofSeconds(30));
    }

    @Override
    public Subscription start() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(
                () -> reader.findByStatus(InboxItemStatus.PUBLISHING).map(this::toEvent).forEach(handler::handle),
                0, interval.toMillis(), TimeUnit.MILLISECONDS
        );
        return new Subscription() {
            public void cancel()    { scheduler.shutdown(); }
            public boolean isActive() { return !scheduler.isShutdown(); }
        };
    }

    private InboxItemStored toEvent(InboxItem inboxItem) {
        return InboxItemStored.builder()
                .idempotencyKey(inboxItem.idempotencyKey())
                .build();
    }
}
