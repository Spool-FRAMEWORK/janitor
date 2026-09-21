package software.spool.janitor.internal.port.decorator;

import software.spool.core.exception.InboxReadException;
import software.spool.core.exception.SpoolException;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.vo.Envelope;
import software.spool.core.port.inbox.InboxStatusQuery;

import java.time.Instant;
import java.util.Collection;

public class SafeInboxStatusQuery implements InboxStatusQuery {
    private final InboxStatusQuery reader;

    public SafeInboxStatusQuery(InboxStatusQuery reader) {
        this.reader = reader;
    }

    public static SafeInboxStatusQuery of(InboxStatusQuery reader) {
        return new SafeInboxStatusQuery(reader);
    }

    @Override
    public Collection<Envelope> findByStatus(EnvelopeStatus status) throws InboxReadException {
        try {
            return reader.findByStatus(status);
        } catch (SpoolException e) {
            throw e;
        } catch (Exception e) {
            throw new InboxReadException(e.getMessage(), e);
        }
    }

    @Override
    public Collection<Envelope> findByStatusModifiedBefore(EnvelopeStatus status, Instant limit) throws InboxReadException {
        try {
            return reader.findByStatusModifiedBefore(status, limit);
        } catch (SpoolException e) {
            throw e;
        } catch (Exception e) {
            throw new InboxReadException(e.getMessage(), e);
        }
    }
}
