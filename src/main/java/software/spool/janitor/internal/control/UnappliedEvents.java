package software.spool.janitor.internal.control;

import software.spool.core.model.event.EnvelopePersisted;
import software.spool.core.model.failure.EnvelopeQuarantined;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The events of one janitor round that could not be applied to the inbox.
 *
 * <p>
 * The steps add to it as they fail, so what they added survives a later step that fails the whole round.
 * </p>
 */
public final class UnappliedEvents {
    private final List<EnvelopePersisted> persisted = new ArrayList<>();
    private final List<EnvelopeQuarantined> quarantined = new ArrayList<>();

    public void persisted(Collection<EnvelopePersisted> events) {
        persisted.addAll(events);
    }

    public void quarantined(Collection<EnvelopeQuarantined> events) {
        quarantined.addAll(events);
    }

    public EventsDTO toEvents() {
        return new EventsDTO(List.copyOf(persisted), List.copyOf(quarantined));
    }
}
