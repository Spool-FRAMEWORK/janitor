package software.spool.janitor.internal.control;

import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.vo.Envelope;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.pipeline.PipelineContext;
import software.spool.core.pipeline.Step;
import software.spool.core.port.inbox.InboxStatusQuery;
import software.spool.core.port.metrics.MetricsRegistry;
import software.spool.core.port.metrics.SpoolMetrics;
import software.spool.core.utils.routing.ErrorRouter;
import software.spool.janitor.api.quarantine.QuarantinedEnvelopePurger;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class PurgeExpiredQuarantineStep implements Step<PipelineContext, PipelineContext> {
    private final ErrorRouter errorRouter;
    private final Duration ttl;
    private final InboxStatusQuery reader;
    private final QuarantinedEnvelopePurger purger;
    private final MetricsRegistry.CounterMetric recordsCleaned;

    public PurgeExpiredQuarantineStep(ErrorRouter errorRouter, Duration ttl, InboxStatusQuery reader,
                                      QuarantinedEnvelopePurger purger, MetricsRegistry.CounterMetric recordsCleaned) {
        this.errorRouter = errorRouter;
        this.ttl = ttl;
        this.reader = reader;
        this.purger = purger;
        this.recordsCleaned = recordsCleaned;
    }

    @Override
    public PipelineContext apply(PipelineContext context) {
        try {
            List<IdempotencyKey> expired = reader.findByStatusModifiedBefore(EnvelopeStatus.QUARANTINED, Instant.now().minus(ttl))
                    .stream()
                    .map(Envelope::idempotencyKey)
                    .toList();
            if (expired.isEmpty()) return context;
            Collection<IdempotencyKey> purged = purger.purge(expired);
            if (!purged.isEmpty()) {
                recordsCleaned.add(purged.size(), Map.of(SpoolMetrics.Attributes.REASON, "quarantine_expired"));
            }
            return context;
        } catch (Exception e) {
            errorRouter.dispatch(e);
            throw e;
        }
    }
}
