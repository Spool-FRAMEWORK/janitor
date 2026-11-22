package software.spool.janitor.api.fixture;

import software.spool.core.exception.InboxUpdateException;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.vo.Envelope;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.port.inbox.InboxUpdater;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Fails the updates to a status the times a test asks, or always, and otherwise updates a fake inbox and counts each move. */
public final class FlakyInboxUpdater implements InboxUpdater {
    private final FakeInbox inbox;
    private final Map<EnvelopeStatus, Integer> failuresLeft = new EnumMap<>(EnvelopeStatus.class);
    private final Set<EnvelopeStatus> alwaysFailing = new HashSet<>();
    private final List<Move> moves = new ArrayList<>();

    public FlakyInboxUpdater(FakeInbox inbox) {
        this.inbox = inbox;
    }

    public void failNext(EnvelopeStatus status, int times) {
        failuresLeft.put(status, times);
    }

    public void failAlways(EnvelopeStatus status) {
        alwaysFailing.add(status);
    }

    @Override
    public Collection<Envelope> update(Collection<IdempotencyKey> idempotencyKeys, EnvelopeStatus status) {
        if (alwaysFailing.contains(status)) throw new InboxUpdateException(idempotencyKeys, "inbox unavailable");
        int left = failuresLeft.getOrDefault(status, 0);
        if (left > 0) {
            failuresLeft.put(status, left - 1);
            throw new InboxUpdateException(idempotencyKeys, "inbox unavailable");
        }
        Collection<Envelope> moved = inbox.update(idempotencyKeys, status);
        moved.forEach(envelope -> moves.add(new Move(envelope.idempotencyKey(), status)));
        return moved;
    }

    public long timesMoved(IdempotencyKey idempotencyKey, EnvelopeStatus status) {
        return moves.stream().filter(move -> move.equals(new Move(idempotencyKey, status))).count();
    }

    private record Move(IdempotencyKey idempotencyKey, EnvelopeStatus status) {
    }
}
