package software.spool.janitor.api.builder;

import org.junit.jupiter.api.Test;
import software.spool.core.port.bus.EventPublisher;
import software.spool.core.port.bus.EventSubscriber;
import software.spool.core.port.inbox.InboxEnvelopeRemover;
import software.spool.core.port.inbox.InboxUpdater;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

class PollingJanitorBuilderTest {

    @Test
    void create_withoutThresholdNorTtl_usesTheDefaultsOfTheStepsInsteadOfFailing() {
        assertThatCode(() -> builder().create()).doesNotThrowAnyException();
    }

    @Test
    void create_withOnlyTheThreshold_leavesTheTtlToItsDefault() {
        assertThatCode(() -> builder().withMillisecondsThreshold(60_000).create()).doesNotThrowAnyException();
    }

    @Test
    void create_withOnlyTheTtl_leavesTheThresholdToItsDefault() {
        assertThatCode(() -> builder().withMillisecondsTtl(86_400_000).create()).doesNotThrowAnyException();
    }

    @Test
    void create_withBothValues_works() {
        assertThatCode(() -> builder().withMillisecondsThreshold(60_000).withMillisecondsTtl(86_400_000).create())
                .doesNotThrowAnyException();
    }

    private static PollingJanitorBuilder builder() {
        return JanitorBuilderFactory.watchdog(null, "janitor-test")
                .polling()
                .from(status -> List.of())
                .with(mock(InboxUpdater.class))
                .removeWith(mock(InboxEnvelopeRemover.class))
                .on(mock(EventPublisher.class))
                .subscribeWith(mock(EventSubscriber.class))
                .every(Duration.ofSeconds(1));
    }
}
