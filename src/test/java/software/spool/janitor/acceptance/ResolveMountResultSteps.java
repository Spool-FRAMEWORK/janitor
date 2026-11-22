package software.spool.janitor.acceptance;

import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import software.spool.core.adapter.jackson.PayloadDeserializerFactory;
import software.spool.core.adapter.jackson.RecordSerializerFactory;
import software.spool.core.adapter.memory.InMemoryQuarantineRepository;
import software.spool.core.model.Event;
import software.spool.core.model.EnvelopeStatus;
import software.spool.core.model.quarantine.QuarantineLocation;
import software.spool.core.model.quarantine.QuarantineReason;
import software.spool.core.model.quarantine.QuarantineResolution;
import software.spool.core.model.quarantine.QuarantineStatus;
import software.spool.core.model.quarantine.QuarantinedRecord;
import software.spool.core.model.quarantine.Resolver;
import software.spool.core.model.vo.Envelope;
import software.spool.core.model.vo.EventMetadata;
import software.spool.core.model.vo.EventMetadataKey;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.model.vo.PartitionKey;
import software.spool.core.port.authentication.Identity;
import software.spool.core.port.authentication.RequestAuthenticator;
import software.spool.core.port.bus.EventPublisher;
import software.spool.core.port.http.Caller;
import software.spool.core.port.http.HttpMethod;
import software.spool.core.port.http.HttpRequest;
import software.spool.core.port.http.HttpResponse;
import software.spool.core.port.http.HttpRoute;
import software.spool.core.port.quarantine.QuarantinedResultWriter;
import software.spool.core.utils.media.MediaTypes;
import software.spool.janitor.api.fixture.FakeDataMart;
import software.spool.janitor.api.fixture.FakeInbox;
import software.spool.janitor.api.quarantine.QuarantinePageSizes;
import software.spool.janitor.api.quarantine.QuarantineRoles;
import software.spool.janitor.api.quarantine.QuarantinedEnvelopes;
import software.spool.janitor.internal.adapter.input.http.HttpQuarantineServer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class ResolveMountResultSteps {
    private static final String PROVIDER = "people";
    private static final String RESOLVE_ROLE = "quarantine-resolver";
    private static final String BYPASS_ROLE = "quarantine-bypasser";
    private static final String READ_ROLE = "quarantine-reader";
    private static final int UNPROCESSABLE = 422;
    private static final int CONFLICT = 409;

    private final InMemoryQuarantineRepository quarantineRepository = new InMemoryQuarantineRepository();
    private final FakeInbox inbox = new FakeInbox();
    private final EventPublisher publisher = new EventPublisher() {
        @Override
        public <E extends Event> void publish(E event) {
        }
    };
    private Optional<FakeDataMart> dataMart = Optional.empty();
    private boolean janitorWritesToTheDataMart = true;
    private IdempotencyKey resultKey;
    private Map<String, Object> resultContent;
    private byte[] amendedContent;
    private Optional<HttpResponse> lastAnswer = Optional.empty();

    @Given("the data mart {string} is partitioned by {string}")
    public void theDataMartIsPartitionedBy(String name, String attribute) {
        dataMart = Optional.of(new FakeDataMart(name, attribute));
    }

    @Given("this result of {string} is pending in quarantine because the attribute {string} is missing:")
    public void thisResultIsPendingInQuarantine(String name, String attribute, DataTable result) {
        resultContent = contentOf(result);
        byte[] content = RecordSerializerFactory.record().serialize(resultContent);
        resultKey = IdempotencyKey.of(name, content);
        Envelope envelope = new Envelope(resultKey, new EventMetadata()
                .set(EventMetadataKey.SOURCE, name).set(EventMetadataKey.CORRELATION_ID, resultKey.value()),
                MediaTypes.JSON, content, EnvelopeStatus.QUARANTINED, 0, Instant.now(), Instant.now());
        PartitionKey day = PartitionKey.ofEntriesWithoutDate().with("day", String.valueOf(resultContent.get("day"))).build();
        quarantineRepository.save(QuarantinedRecord.pending(envelope, QuarantineLocation.dataMart(name, day),
                QuarantineReason.MISSING_PARTITION_ATTRIBUTE,
                List.of("Partition attribute " + attribute + " is missing or null in the mount result"), Instant.now()));
    }

    @Given("the data mart {string} is now partitioned by {string}")
    public void theDataMartIsNowPartitionedBy(String name, String attribute) {
        dataMart.orElseThrow().partitionBy(attribute);
    }

    @Given("the janitor cannot write to {string}")
    public void theJanitorCannotWriteTo(String name) {
        janitorWritesToTheDataMart = false;
    }

    @When("{string} republishes the result")
    public void republishesTheResult(String person) {
        lastAnswer = Optional.of(post(person, "republish", new byte[0]));
    }

    @When("{string} amends the result with:")
    public void amendsTheResultWith(String person, DataTable amended) {
        amendedContent = RecordSerializerFactory.record().serialize(contentOf(amended));
        lastAnswer = Optional.of(post(person, "amend", amendedContent));
    }

    @When("{string} lets the result through because {string}")
    public void letsTheResultThrough(String person, String reason) {
        lastAnswer = Optional.of(post(person, "bypass", ("{\"reason\":\"" + reason + "\"}").getBytes(StandardCharsets.UTF_8)));
    }

    @When("{string} purges the result")
    public void purgesTheResult(String person) {
        lastAnswer = Optional.of(post(person, "purge", new byte[0]));
    }

    @Then("{string} has the result in the partition {string}")
    public void hasTheResultInThePartition(String name, String partition) {
        assertThat(dataMart.orElseThrow().written()).singleElement().satisfies(written -> {
            assertThat(written.partition()).isEqualTo(partitionOf(partition));
            assertThat(written.content()).isEqualTo(resultContent);
        });
    }

    @Then("{string} has the amended result in the partition {string}")
    public void hasTheAmendedResultInThePartition(String name, String partition) {
        assertThat(dataMart.orElseThrow().written()).singleElement().satisfies(written -> {
            assertThat(written.partition()).isEqualTo(partitionOf(partition));
            assertThat(written.content()).isEqualTo(PayloadDeserializerFactory.json().asMap().deserialize(amendedContent));
        });
    }

    @Then("{string} has no results")
    public void hasNoResults(String name) {
        assertThat(dataMart.orElseThrow().written()).isEmpty();
    }

    @Then("the quarantine of the result is republished by {string}")
    public void theQuarantineIsRepublishedBy(String person) {
        assertThat(resultRecord().resolution()).isInstanceOfSatisfying(QuarantineResolution.Republished.class,
                republished -> assertThat(republished.by()).isEqualTo(Resolver.identified(person, PROVIDER)));
    }

    @Then("the quarantine of the result is corrected by {string}")
    public void theQuarantineIsCorrectedBy(String person) {
        assertThat(resultRecord().resolution()).isInstanceOfSatisfying(QuarantineResolution.Corrected.class,
                corrected -> assertThat(corrected.by()).isEqualTo(Resolver.identified(person, PROVIDER)));
    }

    @Then("the quarantine of the result points to the amended result")
    public void theQuarantinePointsToTheAmendedResult() {
        String name = dataMart.orElseThrow().dataMart();
        assertThat(resultRecord().resolution()).isInstanceOfSatisfying(QuarantineResolution.Corrected.class,
                corrected -> assertThat(corrected.correctedTo()).isEqualTo(IdempotencyKey.of(name, amendedContent)));
    }

    @Then("the quarantine of the result is purged by {string}")
    public void theQuarantineIsPurgedBy(String person) {
        assertThat(resultRecord().resolution()).isInstanceOfSatisfying(QuarantineResolution.Purged.class,
                purged -> assertThat(purged.by()).isEqualTo(Resolver.identified(person, PROVIDER)));
    }

    @Then("the quarantine of the result is still pending")
    public void theQuarantineIsStillPending() {
        assertThat(resultRecord().status()).isEqualTo(QuarantineStatus.PENDING);
        assertThat(resultRecord().history()).hasSize(1);
    }

    @Then("{string} is told that the attribute {string} is missing")
    public void isToldThatTheAttributeIsMissing(String person, String attribute) {
        HttpResponse answer = lastAnswer.orElseThrow();
        assertThat(answer.status()).isEqualTo(UNPROCESSABLE);
        assertThat(messageOf(answer)).contains(attribute);
    }

    @Then("{string} is told that a mount result cannot be let through")
    public void isToldThatAMountResultCannotBeLetThrough(String person) {
        HttpResponse answer = lastAnswer.orElseThrow();
        assertThat(answer.status()).isEqualTo(CONFLICT);
        assertThat(messageOf(answer)).contains("cannot be let through");
    }

    private HttpResponse post(String person, String action, byte[] body) {
        HttpRoute route = server().routes().stream()
                .filter(candidate -> candidate.method() == HttpMethod.POST && candidate.path().endsWith("/" + action))
                .findFirst().orElseThrow();
        HttpRequest request = new HttpRequest(HttpMethod.POST, "/spool/quarantine/" + resultKey.value() + "/" + action,
                Map.of(), body, Map.of("key", resultKey.value()), Map.of());
        Caller caller = new Caller.Identified(new Identity(person, Set.of(READ_ROLE, RESOLVE_ROLE, BYPASS_ROLE), PROVIDER));
        return route.handler().handle(request, caller);
    }

    private HttpQuarantineServer server() {
        List<QuarantinedResultWriter> writers = janitorWritesToTheDataMart
                ? dataMart.<QuarantinedResultWriter>map(present -> present).stream().toList()
                : List.of();
        QuarantinedEnvelopes quarantinedEnvelopes = QuarantinedEnvelopes.of(inbox, inbox, quarantineRepository, publisher)
                .rewritingDataMartsWith(writers);
        return new HttpQuarantineServer(quarantinedEnvelopes, quarantinedEnvelopes, quarantinedEnvelopes, quarantinedEnvelopes,
                mock(RequestAuthenticator.class), new QuarantineRoles(READ_ROLE, RESOLVE_ROLE, BYPASS_ROLE), new QuarantinePageSizes(10, 10));
    }

    private QuarantinedRecord resultRecord() {
        return quarantineRepository.find(resultKey).orElseThrow();
    }

    private static String messageOf(HttpResponse answer) {
        return String.valueOf(PayloadDeserializerFactory.json().asMap().deserialize(answer.body()).get("error"));
    }

    private static PartitionKey partitionOf(String partition) {
        String[] attributeAndValue = partition.split("=", 2);
        return PartitionKey.ofEntriesWithoutDate().with(attributeAndValue[0], attributeAndValue[1]).build();
    }

    private static Map<String, Object> contentOf(DataTable table) {
        Map<String, Object> content = new LinkedHashMap<>();
        table.asMaps().getFirst().forEach((field, value) -> content.put(field, isWholeNumber(value) ? Integer.parseInt(value) : value));
        return content;
    }

    private static boolean isWholeNumber(String value) {
        return value.chars().allMatch(Character::isDigit) && !value.isEmpty();
    }
}
