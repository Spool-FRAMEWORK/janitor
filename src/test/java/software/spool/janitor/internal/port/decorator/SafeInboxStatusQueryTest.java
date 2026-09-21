package software.spool.janitor.internal.port.decorator;

import org.junit.jupiter.api.Test;
import software.spool.core.exception.InboxReadException;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.vo.Envelope;
import software.spool.core.port.inbox.InboxStatusQuery;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SafeInboxStatusQueryTest {

    private static final Instant LIMIT = Instant.parse("2026-09-10T00:00:00Z");

    @Test
    void findByStatusModifiedBefore_delegatesInsteadOfFilteringWhatFindByStatusReturns() {
        AtomicInteger everythingReads = new AtomicInteger();
        AtomicInteger filteredReads = new AtomicInteger();
        InboxStatusQuery delegate = new InboxStatusQuery() {
            @Override
            public Collection<Envelope> findByStatus(EnvelopeStatus status) {
                everythingReads.incrementAndGet();
                return List.of();
            }

            @Override
            public Collection<Envelope> findByStatusModifiedBefore(EnvelopeStatus status, Instant limit) {
                filteredReads.incrementAndGet();
                return List.of();
            }
        };

        SafeInboxStatusQuery.of(delegate).findByStatusModifiedBefore(EnvelopeStatus.PERSISTED, LIMIT);

        assertThat(filteredReads).hasValue(1);
        assertThat(everythingReads).hasValue(0);
    }

    @Test
    void findByStatusModifiedBefore_aFailureOfTheDelegate_isReportedAsAnInboxReadException() {
        InboxStatusQuery delegate = new InboxStatusQuery() {
            @Override
            public Collection<Envelope> findByStatus(EnvelopeStatus status) {
                return List.of();
            }

            @Override
            public Collection<Envelope> findByStatusModifiedBefore(EnvelopeStatus status, Instant limit) {
                throw new IllegalStateException("storage down");
            }
        };

        assertThatThrownBy(() -> SafeInboxStatusQuery.of(delegate).findByStatusModifiedBefore(EnvelopeStatus.PERSISTED, LIMIT))
            .isInstanceOf(InboxReadException.class)
            .hasMessageContaining("storage down");
    }
}
