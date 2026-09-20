package software.spool.janitor.api.strategy;

import software.spool.core.adapter.logging.LoggerFactory;
import software.spool.core.model.event.EnvelopePersisted;
import software.spool.core.model.failure.EnvelopeQuarantined;
import software.spool.core.port.bus.EventSubscriber;
import software.spool.core.port.bus.Handler;
import software.spool.core.port.inbox.InboxStatusQuery;
import software.spool.core.port.logging.Logger;
import software.spool.core.utils.polling.CancellationToken;
import software.spool.core.utils.polling.PollingConfiguration;
import software.spool.janitor.internal.control.EventsDTO;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PollingJanitorStrategy implements JanitorStrategy {
    private static final Logger LOG = LoggerFactory.getLogger(PollingJanitorStrategy.class);
    private final EventSubscriber subscriber;
    private final Handler<EventsDTO> janitorScheduleHandler;
    private final PollingConfiguration pollingConfiguration;

    public PollingJanitorStrategy(InboxStatusQuery reader, EventSubscriber subscriber, Handler<EventsDTO> janitorScheduleHandler, PollingConfiguration pollingConfiguration) {
        this.subscriber = Objects.requireNonNull(subscriber);
        this.janitorScheduleHandler = Objects.requireNonNull(janitorScheduleHandler);
        this.pollingConfiguration = Objects.requireNonNullElse(pollingConfiguration, PollingConfiguration.every(Duration.ofSeconds(10)));
    }

    @Override
    public void execute(CancellationToken token) {
        Queue<EnvelopePersisted> persistedEnvelopes = new ConcurrentLinkedQueue<>();
        Queue<EnvelopeQuarantined> quarantinedEnvelopes = new ConcurrentLinkedQueue<>();
        subscriber.subscribe(EnvelopePersisted.class, persistedEnvelopes::add);
        subscriber.subscribe (EnvelopeQuarantined.class, quarantinedEnvelopes::add);
        pollingConfiguration.scheduler().schedule(
                () -> {
                    try {
                        LOG.info("Polling janitor strategy execution started");
                        janitorScheduleHandler.handle(new EventsDTO(drain(persistedEnvelopes), drain(quarantinedEnvelopes)));
                    } catch (Exception e) {
                        LOG.error("Exception occurred while polling janitor strategy", e);
                    }
                },
                pollingConfiguration.policy(),
                token
        );
    }

    /**
     * Takes every event received so far out of the queue. Events published from other threads, or while a
     * cycle is being handled, stay in the queue for the next cycle instead of being lost.
     */
    private static <T> List<T> drain(Queue<T> queue) {
        List<T> batch = new ArrayList<>();
        for (T event; (event = queue.poll()) != null; ) batch.add(event);
        return List.copyOf(batch);
    }
}
