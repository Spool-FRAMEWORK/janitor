package software.spool.janitor.api.strategy;

import software.spool.core.model.event.QuarantinedEnvelopeReplaced;
import software.spool.core.adapter.logging.LoggerFactory;
import software.spool.core.exception.InboxUpdateException;
import software.spool.core.model.Event;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.model.event.EnvelopePersisted;
import software.spool.core.model.failure.EnvelopeQuarantined;
import software.spool.core.port.bus.EventSubscriber;
import software.spool.core.port.inbox.InboxStatusQuery;
import software.spool.core.port.logging.Logger;
import software.spool.core.utils.polling.CancellationToken;
import software.spool.core.utils.polling.PollingConfiguration;
import software.spool.core.utils.routing.ErrorRouter;
import software.spool.janitor.internal.control.EventsDTO;
import software.spool.janitor.internal.control.JanitorCycle;

import java.util.function.Consumer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PollingJanitorStrategy implements JanitorStrategy {
    private static final Logger LOG = LoggerFactory.getLogger(PollingJanitorStrategy.class);
    private final EventSubscriber subscriber;
    private final JanitorCycle cycle;
    private final PollingConfiguration pollingConfiguration;
    private final int maxInboxUpdateAttempts;
    private final ErrorRouter errorRouter;
    private final Map<String, Integer> attemptsByEventId = new HashMap<>();
    private final Consumer<IdempotencyKey> replacedInQuarantine;

    /**
     * @param maxInboxUpdateAttempts how many rounds an event is tried before the janitor stops retrying it and reports
     *                               it to the error router; at least 1
     */
    public PollingJanitorStrategy(InboxStatusQuery reader, EventSubscriber subscriber, JanitorCycle cycle,
                                  PollingConfiguration pollingConfiguration, int maxInboxUpdateAttempts, ErrorRouter errorRouter) {
        this(reader, subscriber, cycle, pollingConfiguration, maxInboxUpdateAttempts, errorRouter, key -> {
        });
    }

    /**
     * @param replacedInQuarantine what to do with the key of a quarantined envelope a new capture took the place of
     *                             (ADR-032): resolving its quarantine as replaced
     */
    public PollingJanitorStrategy(InboxStatusQuery reader, EventSubscriber subscriber, JanitorCycle cycle,
                                  PollingConfiguration pollingConfiguration, int maxInboxUpdateAttempts, ErrorRouter errorRouter,
                                  Consumer<IdempotencyKey> replacedInQuarantine) {
        if (maxInboxUpdateAttempts < 1)
            throw new IllegalArgumentException("maxInboxUpdateAttempts must be at least 1, got " + maxInboxUpdateAttempts);
        this.subscriber = Objects.requireNonNull(subscriber);
        this.cycle = Objects.requireNonNull(cycle);
        this.pollingConfiguration = Objects.requireNonNullElse(pollingConfiguration, PollingConfiguration.every(Duration.ofSeconds(10)));
        this.maxInboxUpdateAttempts = maxInboxUpdateAttempts;
        this.errorRouter = Objects.requireNonNull(errorRouter);
        this.replacedInQuarantine = Objects.requireNonNull(replacedInQuarantine);
    }

    @Override
    public void execute(CancellationToken token) {
        Queue<EnvelopePersisted> persistedEnvelopes = new ConcurrentLinkedQueue<>();
        Queue<EnvelopeQuarantined> quarantinedEnvelopes = new ConcurrentLinkedQueue<>();
        subscriber.subscribe(EnvelopePersisted.class, persistedEnvelopes::add);
        subscriber.subscribe (EnvelopeQuarantined.class, quarantinedEnvelopes::add);
        subscriber.subscribe(QuarantinedEnvelopeReplaced.class, this::markReplaced);
        pollingConfiguration.scheduler().schedule(
                () -> {
                    try {
                        LOG.info("Polling janitor strategy execution started");
                        EventsDTO unapplied = cycle.run(new EventsDTO(drain(persistedEnvelopes), drain(quarantinedEnvelopes)));
                        retry(unapplied, persistedEnvelopes, quarantinedEnvelopes);
                    } catch (Exception e) {
                        LOG.error("Exception occurred while polling janitor strategy", e);
                    }
                },
                pollingConfiguration.schedule(),
                token
        );
    }

    private void markReplaced(QuarantinedEnvelopeReplaced replaced) {
        try {
            replacedInQuarantine.accept(replaced.idempotencyKey());
        } catch (RuntimeException e) {
            errorRouter.dispatch(e);
        }
    }

    /**
     * Puts back in their queues the events the round could not apply, except those that have used up their attempts,
     * which are reported instead. An event applied in this round is forgotten.
     */
    private void retry(EventsDTO unapplied, Queue<EnvelopePersisted> persistedEnvelopes,
                       Queue<EnvelopeQuarantined> quarantinedEnvelopes) {
        Set<String> unappliedIds = new HashSet<>();
        unapplied.envelopesPersisted().forEach(event -> unappliedIds.add(event.eventId()));
        unapplied.envelopesQuarantined().forEach(event -> unappliedIds.add(event.eventId()));
        attemptsByEventId.keySet().retainAll(unappliedIds);
        unapplied.envelopesPersisted().forEach(event -> retry(event, event.idempotencyKey(), persistedEnvelopes));
        unapplied.envelopesQuarantined().forEach(event -> retry(event, event.idempotencyKey(), quarantinedEnvelopes));
    }

    private <E extends Event> void retry(E event, IdempotencyKey idempotencyKey, Queue<E> queue) {
        int attempts = attemptsByEventId.merge(event.eventId(), 1, Integer::sum);
        if (attempts < maxInboxUpdateAttempts) {
            queue.add(event);
            return;
        }
        attemptsByEventId.remove(event.eventId());
        errorRouter.dispatch(new InboxUpdateException(idempotencyKey, "The janitor stopped retrying "
                + event.getClass().getSimpleName() + " for " + idempotencyKey.value() + " after " + attempts + " attempts"));
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
