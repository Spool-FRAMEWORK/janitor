package software.spool.feeder.api.port;

import software.spool.core.exception.InboxReadException;
import software.spool.core.model.InboxItemStatus;
import software.spool.core.model.vo.InboxItem;

import java.util.stream.Stream;

/**
 * Port for reading inbox items by status.
 *
 * <p>
 * Implement this interface to connect the publisher to your inbox storage
 * backend (database, in-memory store, etc.). The polling feeder calls
 * {@link #findByStatus(InboxItemStatus)} periodically to discover items
 * ready for publishing.
 * </p>
 */
public interface InboxReader {
    /**
     * Returns all inbox items matching the given status.
     *
     * @param status the status to filter by; must not be {@code null}
     * @return a stream of matching inbox items
     * @throws InboxReadException if the query fails
     */
    Stream<InboxItem> findByStatus(InboxItemStatus status) throws InboxReadException;
}
