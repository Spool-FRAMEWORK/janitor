package software.spool.feeder.internal.port.decorator;

import software.spool.core.exception.InboxReadException;
import software.spool.core.exception.SpoolException;
import software.spool.core.model.InboxItemStatus;
import software.spool.core.model.vo.InboxItem;
import software.spool.feeder.api.port.InboxReader;

import java.util.stream.Stream;

/**
 * Decorator for {@link InboxReader} that normalises unchecked exceptions
 * into typed {@link InboxReadException} instances.
 *
 * <p>
 * If the delegate throws a {@link SpoolException} subclass, it is re-thrown
 * as-is. Any other {@link Exception} is wrapped in a new
 * {@link InboxReadException}.
 * </p>
 */
public class SafeInboxReader implements InboxReader {
    private final InboxReader reader;

    public SafeInboxReader(InboxReader reader) {
        this.reader = reader;
    }

    @Override
    public Stream<InboxItem> findByStatus(InboxItemStatus status) {
        try {
            return reader.findByStatus(status);
        } catch (SpoolException e) {
            throw e;
        } catch (Exception e) {
            throw new InboxReadException(e.getMessage(), e);
        }
    }

    /**
     * Creates a new {@code SafeInboxReader} wrapping the given delegate.
     *
     * @param reader the reader to wrap; must not be {@code null}
     * @return a new {@code SafeInboxReader} instance
     */
    public static SafeInboxReader of(InboxReader reader) {
        return new SafeInboxReader(reader);
    }
}
