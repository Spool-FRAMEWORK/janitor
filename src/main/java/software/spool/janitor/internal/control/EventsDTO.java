package software.spool.janitor.internal.control;

import software.spool.core.model.event.EnvelopePersisted;
import software.spool.core.model.failure.EnvelopeQuarantined;

import java.util.Collection;
import java.util.List;

public record EventsDTO(
        Collection<EnvelopePersisted> envelopesPersisted,
        Collection<EnvelopeQuarantined> envelopesQuarantined
) {
    public static EventsDTO none() {
        return new EventsDTO(List.of(), List.of());
    }
}
