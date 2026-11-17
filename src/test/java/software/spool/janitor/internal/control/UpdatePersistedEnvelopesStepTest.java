package software.spool.janitor.internal.control;

import org.junit.jupiter.api.Test;
import software.spool.core.exception.InboxUpdateException;
import software.spool.core.model.event.EnvelopePersisted;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.model.vo.PartitionKey;
import software.spool.core.pipeline.PipelineContext;

import javax.management.AttributeNotFoundException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UpdatePersistedEnvelopesStepTest {

    @Test
    void apply_inboxFails_keepsTheEventsAsUnappliedInsteadOfFailing() throws AttributeNotFoundException {
        UpdatePersistedEnvelopesStep step = new UpdatePersistedEnvelopesStep(
                (keys, status) -> { throw new InboxUpdateException(keys, "inbox unavailable"); });
        UnappliedEvents unapplied = new UnappliedEvents();
        EnvelopePersisted event = EnvelopePersisted.builder()
                .idempotencyKey(IdempotencyKey.of("k1"))
                .partitionKey(PartitionKey.ofNow())
                .build();

        step.apply(PipelineContext.empty()
                .with(JanitorScheduleKeys.ENVELOPES_PERSISTED, List.of(event))
                .with(JanitorScheduleKeys.UNAPPLIED_EVENTS, unapplied));

        assertThat(unapplied.toEvents().envelopesPersisted()).containsExactly(event);
    }
}
