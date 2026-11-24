package software.spool.janitor.acceptance;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import software.spool.core.adapter.memory.InMemoryQuarantineRepository;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.event.EnvelopePersisted;
import software.spool.core.model.failure.EnvelopeQuarantined;
import software.spool.core.model.quarantine.QuarantineReason;
import software.spool.core.model.vo.Envelope;
import software.spool.core.model.vo.EventMetadata;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.model.vo.MediaType;
import software.spool.core.model.vo.PartitionKey;
import software.spool.janitor.api.builder.JanitorRounds;
import software.spool.janitor.api.fixture.FakeInbox;
import software.spool.janitor.api.fixture.FlakyInboxUpdater;
import software.spool.janitor.api.fixture.MovableClock;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

public class InboxSteps {
    private final FakeInbox inbox = new FakeInbox();
    private final FlakyInboxUpdater updater = new FlakyInboxUpdater(inbox);
    private final InMemoryQuarantineRepository quarantineRepository = new InMemoryQuarantineRepository();
    private final MovableClock clock = new MovableClock();
    private int maxInboxUpdateAttempts = 3;
    private Optional<Duration> processedKeysWindow = Optional.empty();
    private JanitorRounds janitor;

    @Given("a janitor that remembers the keys it marked for {int} minutes")
    public void aJanitorThatRemembers(int minutes) {
        processedKeysWindow = Optional.of(Duration.ofMinutes(minutes));
    }

    @Given("the envelope {string} is captured in the inbox")
    public void theEnvelopeIsCaptured(String key) {
        inbox.with(captured(key));
    }

    @Given("these envelopes are captured in the inbox:")
    public void theseEnvelopesAreCaptured(DataTable envelopes) {
        envelopes.asMaps().forEach(row -> inbox.with(captured(row.get("envelope"))));
    }

    @Given("the inbox fails the next time envelopes are quarantined")
    public void theInboxFailsOnceWhenQuarantining() {
        updater.failNext(EnvelopeStatus.QUARANTINED, 1);
    }

    @Given("the inbox fails the next time envelopes are marked as persisted")
    public void theInboxFailsOnceWhenMarkingPersisted() {
        updater.failNext(EnvelopeStatus.PERSISTED, 1);
    }

    @Given("the inbox always fails when envelopes are marked as persisted")
    public void theInboxAlwaysFailsWhenMarkingPersisted() {
        updater.failAlways(EnvelopeStatus.PERSISTED);
    }

    @Given("the janitor tries each event {int} time(s)")
    public void theJanitorTriesEachEvent(int attempts) {
        maxInboxUpdateAttempts = attempts;
    }

    @When("the janitor learns that {string} must be quarantined")
    public void theJanitorLearnsItMustBeQuarantined(String key) {
        janitor().learn(EnvelopeQuarantined.builder()
                .idempotencyKey(IdempotencyKey.of(key))
                .reason(QuarantineReason.VALIDATION_FAILED)
                .violations(List.of("amount must be positive"))
                .build());
    }

    @When("the janitor learns that {string} was persisted")
    public void theJanitorLearnsItWasPersisted(String key) {
        janitor().learn(EnvelopePersisted.builder()
                .idempotencyKey(IdempotencyKey.of(key))
                .partitionKey(PartitionKey.ofNow())
                .build());
    }

    @When("the janitor runs {int} round(s)")
    public void theJanitorRuns(int rounds) {
        janitor().run(rounds);
    }

    @When("{int} minutes pass")
    public void minutesPass(int minutes) {
        clock.advance(Duration.ofMinutes(minutes));
    }

    @Then("the inbox marked {string} as persisted once")
    public void theInboxMarkedItOnce(String key) {
        theInboxMarkedIt(key, 1);
    }

    @Then("the inbox marked {string} as persisted {int} times")
    public void theInboxMarkedIt(String key, int times) {
        assertThat(updater.timesMoved(IdempotencyKey.of(key), EnvelopeStatus.PERSISTED)).isEqualTo(times);
    }

    @Then("{string} is quarantined in the inbox")
    public void isQuarantined(String key) {
        assertThat(statusOf(key)).contains(EnvelopeStatus.QUARANTINED);
    }

    @Then("{string} is persisted in the inbox")
    public void isPersisted(String key) {
        assertThat(statusOf(key)).contains(EnvelopeStatus.PERSISTED);
    }

    @Then("{string} is still captured in the inbox")
    public void isStillCaptured(String key) {
        assertThat(statusOf(key)).contains(EnvelopeStatus.CAPTURED);
    }

    @Then("{string} has a single quarantine entry")
    public void hasASingleQuarantineEntry(String key) {
        assertThat(quarantineRepository.find(IdempotencyKey.of(key)).orElseThrow().history()).hasSize(1);
    }

    @Then("the janitor reports that it gave up on {string}")
    public void theJanitorReportsItGaveUp(String key) {
        assertThat(janitor().reported()).singleElement()
                .satisfies(exception -> assertThat(exception).hasMessageContaining(key));
    }

    private JanitorRounds janitor() {
        if (janitor == null) janitor = new JanitorRounds(inbox, updater, quarantineRepository, maxInboxUpdateAttempts, processedKeysWindow, clock);
        return janitor;
    }

    private Optional<EnvelopeStatus> statusOf(String key) {
        return inbox.stored(key).map(Envelope::status);
    }

    private static Envelope captured(String key) {
        Instant capturedAt = Instant.now();
        return new Envelope(IdempotencyKey.of(key), new EventMetadata(), MediaType.of("application/json"), "{}".getBytes(),
                EnvelopeStatus.CAPTURED, 0, capturedAt, capturedAt);
    }
}
