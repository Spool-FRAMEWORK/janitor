package software.spool.feeder.api.strategy;

import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.event.EnvelopePersisted;
import software.spool.core.model.failure.EnvelopeQuarantined;
import software.spool.core.model.vo.Envelope;
import software.spool.core.port.bus.Destination;
import software.spool.core.port.bus.EventSubscriber;
import software.spool.core.port.bus.Handler;
import software.spool.core.port.inbox.InboxStatusQuery;
import software.spool.core.utils.polling.CancellationToken;
import software.spool.core.utils.polling.PollingConfiguration;

import java.time.Duration;
import java.util.*;

public class PollingFeederStrategy implements FeederStrategy {
    private final InboxStatusQuery reader;
    private final EventSubscriber subscriber;
    private final Handler<Collection<EnvelopePersisted>> persistedEnvelopesHandler;
    private final Handler<Collection<EnvelopeQuarantined>> quarantineEnvelopesHandler;
    private final Handler<Collection<Envelope>> stuckEnvelopesHandler;
    private final PollingConfiguration pollingConfiguration;

    public PollingFeederStrategy(InboxStatusQuery reader, EventSubscriber subscriber, Handler<Collection<EnvelopePersisted>> persistedEnvelopesHandler, Handler<Collection<EnvelopeQuarantined>> quarantineEnvelopesHandler, Handler<Collection<Envelope>> stuckEnvelopesHandler, PollingConfiguration pollingConfiguration) {
        this.reader = Objects.requireNonNull(reader);
        this.subscriber = subscriber;
        this.persistedEnvelopesHandler = persistedEnvelopesHandler;
        this.quarantineEnvelopesHandler = quarantineEnvelopesHandler;
        this.stuckEnvelopesHandler = Objects.requireNonNull(stuckEnvelopesHandler);
        this.pollingConfiguration = Objects.requireNonNullElse(pollingConfiguration, PollingConfiguration.every(Duration.ofSeconds(10)));
    }

    @Override
    public void execute(CancellationToken token) {
        List<EnvelopePersisted> persistedEnvelopes = new ArrayList<>();
        List<EnvelopeQuarantined> quarantinedEnvelopes = new ArrayList<>();
        subscriber.subscribe(new Destination("spool." + EnvelopePersisted.class.getSimpleName()),
                EnvelopePersisted.class,
                e -> persistedEnvelopes.add(e.payload()));
        subscriber.subscribe(new Destination("spool." + EnvelopeQuarantined.class.getSimpleName()),
                EnvelopeQuarantined.class,
                e -> quarantinedEnvelopes.add(e.payload()));
        pollingConfiguration.scheduler().schedule(
                () -> {
                    persistedEnvelopesHandler.handle(Collections.unmodifiableList(persistedEnvelopes));
                    quarantineEnvelopesHandler.handle(Collections.unmodifiableList(quarantinedEnvelopes));
                    stuckEnvelopesHandler.handle(reader.findByStatus(EnvelopeStatus.CAPTURED));
                    persistedEnvelopes.clear();
                    quarantinedEnvelopes.clear();
                },
                pollingConfiguration.policy(),
                token
        );
    }
}
