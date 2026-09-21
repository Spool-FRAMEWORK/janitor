package software.spool.janitor.api.fixture;

import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.vo.Envelope;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.port.inbox.InboxEnvelopeRemover;
import software.spool.core.port.inbox.InboxReader;
import software.spool.core.port.inbox.InboxUpdater;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class FakeInbox implements InboxReader, InboxUpdater, InboxEnvelopeRemover {
    private final Map<IdempotencyKey, Envelope> envelopes = new LinkedHashMap<>();

    public FakeInbox with(Envelope... items) {
        for (Envelope item : items) {
            envelopes.put(item.idempotencyKey(), item);
        }
        return this;
    }

    public Optional<Envelope> stored(String key) {
        return Optional.ofNullable(envelopes.get(IdempotencyKey.of(key)));
    }

    @Override
    public Optional<Envelope> findById(IdempotencyKey idempotencyKey) {
        return Optional.ofNullable(envelopes.get(idempotencyKey));
    }

    @Override
    public Collection<Envelope> findByIds(Collection<IdempotencyKey> idempotencyKeys) {
        return idempotencyKeys.stream().map(envelopes::get).filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public Collection<Envelope> findByStatus(EnvelopeStatus status) {
        return envelopes.values().stream().filter(envelope -> envelope.status() == status).toList();
    }

    @Override
    public Envelope update(Envelope envelope) {
        envelopes.put(envelope.idempotencyKey(), envelope);
        return envelope;
    }

    @Override
    public Collection<Envelope> update(Collection<IdempotencyKey> idempotencyKeys, EnvelopeStatus status) {
        List<Envelope> moved = new ArrayList<>();
        for (IdempotencyKey key : idempotencyKeys) {
            Envelope existing = envelopes.get(key);
            if (existing == null) continue;
            Envelope changed = existing.withStatus(status);
            envelopes.put(key, changed);
            moved.add(changed);
        }
        return moved;
    }

    @Override
    public Collection<Envelope> remove(Collection<IdempotencyKey> idempotencyKeys) {
        List<Envelope> removed = new ArrayList<>();
        for (IdempotencyKey key : idempotencyKeys) {
            Envelope existing = envelopes.remove(key);
            if (existing != null) removed.add(existing);
        }
        return removed;
    }
}
