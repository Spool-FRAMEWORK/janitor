package software.spool.janitor.internal.control;

import software.spool.core.adapter.logging.LoggerFactory;
import software.spool.core.exception.InboxUpdateException;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.event.EnvelopePersisted;
import software.spool.core.model.vo.Envelope;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.pipeline.PipelineContext;
import software.spool.core.pipeline.Step;
import software.spool.core.port.idempotency.ProcessedKeyWindow;
import software.spool.core.port.inbox.InboxUpdater;
import software.spool.core.port.logging.Logger;

import javax.management.AttributeNotFoundException;
import java.util.Collection;
import java.util.List;

/**
 * Marks as PERSISTED the envelopes the ingester wrote, except those whose key the janitor remembers, so a repeated
 * EnvelopePersisted does not start their TTL again. A key is remembered once the inbox applied it (ADR-007).
 */
public class UpdatePersistedEnvelopesStep implements Step<PipelineContext, PipelineContext> {
    private static final Logger LOG = LoggerFactory.getLogger(UpdatePersistedEnvelopesStep.class);
    private final InboxUpdater updater;
    private final ProcessedKeyWindow processedKeys;

    public UpdatePersistedEnvelopesStep(InboxUpdater updater, ProcessedKeyWindow processedKeys) {
        this.updater = updater;
        this.processedKeys = processedKeys;
    }

    @Override
    public PipelineContext apply(PipelineContext context) throws AttributeNotFoundException {
        Collection<EnvelopePersisted> events = context.require(JanitorScheduleKeys.ENVELOPES_PERSISTED);
        List<IdempotencyKey> keys = events.stream()
                .map(EnvelopePersisted::idempotencyKey)
                .filter(key -> !processedKeys.remembers(key))
                .distinct()
                .toList();
        if (keys.isEmpty()) return context;
        try {
            Collection<Envelope> result = updater.update(keys, EnvelopeStatus.PERSISTED);
            result.forEach(envelope -> processedKeys.remember(envelope.idempotencyKey()));
            LOG.info("Updated {} persisted envelopes from inbox", result.size());
        } catch (InboxUpdateException exception) {
            LOG.warn("Could not mark {} envelopes as persisted in the inbox; trying again in the next round: {}",
                    events.size(), exception.getMessage());
            context.require(JanitorScheduleKeys.UNAPPLIED_EVENTS).persisted(events);
        }
        return context;
    }
}
