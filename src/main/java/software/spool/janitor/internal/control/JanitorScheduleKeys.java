package software.spool.janitor.internal.control;

import software.spool.core.model.event.EnvelopePersisted;
import software.spool.core.model.failure.EnvelopeQuarantined;
import software.spool.core.pipeline.ContextKey;

import java.util.Collection;

public class JanitorScheduleKeys {
    private JanitorScheduleKeys() {}

    public static final ContextKey<Collection<EnvelopePersisted>> ENVELOPES_PERSISTED = ContextKey.of("envelopesPersisted");
    public static final ContextKey<Collection<EnvelopeQuarantined>> ENVELOPES_QUARANTINED = ContextKey.of("envelopesQuarantined");
    public static final ContextKey<UnappliedEvents> UNAPPLIED_EVENTS = ContextKey.of("unappliedEvents");
}
