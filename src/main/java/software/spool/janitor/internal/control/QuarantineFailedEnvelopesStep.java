package software.spool.janitor.internal.control;

import software.spool.core.adapter.logging.LoggerFactory;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.failure.EnvelopeQuarantined;
import software.spool.core.model.quarantine.QuarantineLocation;
import software.spool.core.model.vo.Envelope;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.pipeline.PipelineContext;
import software.spool.core.pipeline.Step;
import software.spool.core.port.inbox.InboxUpdater;
import software.spool.core.port.logging.Logger;
import software.spool.core.port.metrics.MetricsRegistry;
import software.spool.core.port.metrics.SpoolMetrics;
import software.spool.core.port.quarantine.QuarantineRepository;
import software.spool.core.utils.quarantine.QuarantineRecorder;

import javax.management.AttributeNotFoundException;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class QuarantineFailedEnvelopesStep implements Step<PipelineContext, PipelineContext> {
    private static final Logger LOG = LoggerFactory.getLogger(QuarantineFailedEnvelopesStep.class);
    private final InboxUpdater updater;
    private final QuarantineRecorder recorder;
    private final MetricsRegistry.CounterMetric recordsCleaned;

    public QuarantineFailedEnvelopesStep(InboxUpdater updater, QuarantineRepository quarantineRepository, MetricsRegistry.CounterMetric recordsCleaned) {
        this.updater = updater;
        this.recorder = new QuarantineRecorder(quarantineRepository);
        this.recordsCleaned = recordsCleaned;
    }

    @Override
    public PipelineContext apply(PipelineContext context) throws AttributeNotFoundException {
        Collection<EnvelopeQuarantined> events = context.require(JanitorScheduleKeys.ENVELOPES_QUARANTINED);
        if (events.isEmpty()) return context;
        Map<IdempotencyKey, EnvelopeQuarantined> lastEventByKey = events.stream()
                .filter(event -> event.location() instanceof QuarantineLocation.Inbox)
                .collect(Collectors.toMap(EnvelopeQuarantined::idempotencyKey, Function.identity(), (first, last) -> last));
        Collection<Envelope> quarantined = updater.update(lastEventByKey.keySet(), EnvelopeStatus.QUARANTINED);
        Instant quarantinedAt = Instant.now();
        quarantined.forEach(envelope -> {
            EnvelopeQuarantined event = lastEventByKey.get(envelope.idempotencyKey());
            recorder.record(envelope, QuarantineLocation.inbox(), event.reason(), event.violations(), quarantinedAt);
        });
        LOG.info("Quarantined {} envelopes", quarantined.size());
        if (!quarantined.isEmpty()) {
            recordsCleaned.add(quarantined.size(), Map.of(SpoolMetrics.Attributes.REASON, "quarantine"));
        }
        return context;
    }
}
