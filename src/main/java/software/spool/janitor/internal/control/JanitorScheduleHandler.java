package software.spool.janitor.internal.control;

import software.spool.core.pipeline.Pipeline;
import software.spool.core.pipeline.PipelineContext;
import software.spool.core.port.metrics.MetricsRegistry;
import software.spool.core.utils.routing.ErrorRouter;

import java.util.Map;

public class JanitorScheduleHandler implements JanitorCycle {
    private final Pipeline<PipelineContext, PipelineContext> pipeline;
    private final ErrorRouter errorRouter;
    private final MetricsRegistry.CounterMetric cyclesCompleted;
    private final MetricsRegistry.CounterMetric cyclesFailed;
    private final MetricsRegistry.TimerMetric cycleDuration;

    public JanitorScheduleHandler(Pipeline<PipelineContext, PipelineContext> pipeline, ErrorRouter errorRouter, MetricsRegistry.CounterMetric cyclesCompleted, MetricsRegistry.CounterMetric cyclesFailed, MetricsRegistry.TimerMetric cycleDuration) {
        this.pipeline = pipeline;
        this.errorRouter = errorRouter;
        this.cyclesCompleted = cyclesCompleted;
        this.cyclesFailed = cyclesFailed;
        this.cycleDuration = cycleDuration;
    }

    @Override
    public EventsDTO run(EventsDTO events) {
        long start = System.nanoTime();
        UnappliedEvents unapplied = new UnappliedEvents();
        try {
            pipeline.execute(PipelineContext.empty()
                    .with(JanitorScheduleKeys.ENVELOPES_PERSISTED, events.envelopesPersisted())
                    .with(JanitorScheduleKeys.ENVELOPES_QUARANTINED, events.envelopesQuarantined())
                    .with(JanitorScheduleKeys.UNAPPLIED_EVENTS, unapplied));
            cyclesCompleted.increment(Map.of());
        } catch (Exception e) {
            cyclesFailed.increment(Map.of());
            errorRouter.dispatch(e);
        } finally {
            cycleDuration.record((System.nanoTime() - start) / 1_000_000, Map.of());
        }
        return unapplied.toEvents();
    }
}
