package software.spool.janitor.internal.control;

import software.spool.core.adapter.logging.LoggerFactory;
import software.spool.core.exception.InboxUpdateException;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.event.EnvelopePersisted;
import software.spool.core.model.vo.Envelope;
import software.spool.core.pipeline.PipelineContext;
import software.spool.core.pipeline.Step;
import software.spool.core.port.inbox.InboxUpdater;
import software.spool.core.port.logging.Logger;

import javax.management.AttributeNotFoundException;
import java.util.Collection;

public class UpdatePersistedEnvelopesStep implements Step<PipelineContext, PipelineContext> {
    private static final Logger LOG = LoggerFactory.getLogger(UpdatePersistedEnvelopesStep.class);
    private final InboxUpdater updater;

    public UpdatePersistedEnvelopesStep(InboxUpdater updater) {
        this.updater = updater;
    }

    @Override
    public PipelineContext apply(PipelineContext context) throws AttributeNotFoundException {
        Collection<EnvelopePersisted> events = context.require(JanitorScheduleKeys.ENVELOPES_PERSISTED);
        if (events.isEmpty()) return context;
        try {
            Collection<Envelope> result = updater.update(events.stream().map(EnvelopePersisted::idempotencyKey).toList(),
                    EnvelopeStatus.PERSISTED);
            LOG.info("Updated {} persisted envelopes from inbox", result.size());
        } catch (InboxUpdateException exception) {
            LOG.warn("Could not mark {} envelopes as persisted in the inbox; trying again in the next round: {}",
                    events.size(), exception.getMessage());
            context.require(JanitorScheduleKeys.UNAPPLIED_EVENTS).persisted(events);
        }
        return context;
    }
}
