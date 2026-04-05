package software.spool.feeder.api.strategy;

import software.spool.core.model.InboxItemStatus;
import software.spool.core.model.event.InboxItemStored;
import software.spool.core.model.vo.InboxItem;
import software.spool.core.port.bus.Handler;
import software.spool.core.utils.polling.CancellationToken;
import software.spool.core.utils.polling.PollingConfiguration;
import software.spool.feeder.api.port.InboxReader;

import java.time.Duration;
import java.util.Objects;

public class PollingFeederStrategy implements FeederStrategy {
    private final InboxReader reader;
    private final Handler<InboxItemStored> handler;
    private final PollingConfiguration pollingConfiguration;

    public PollingFeederStrategy(InboxReader reader, Handler<InboxItemStored> handler, PollingConfiguration pollingConfiguration) {
        this.reader = Objects.requireNonNull(reader);
        this.handler = Objects.requireNonNull(handler);
        this.pollingConfiguration = Objects.requireNonNullElse(pollingConfiguration, PollingConfiguration.every(Duration.ofSeconds(10)));
    }

    @Override
    public void execute(CancellationToken token) {
        pollingConfiguration.scheduler().schedule(
                () -> reader.findByStatus(InboxItemStatus.UNPUBLISHED).map(this::toEvent).forEach(handler::handle),
                pollingConfiguration.policy(),
                token
        );
    }

    private InboxItemStored toEvent(InboxItem inboxItem) {
        return InboxItemStored.builder()
                .idempotencyKey(inboxItem.idempotencyKey())
                .build();
    }
}
