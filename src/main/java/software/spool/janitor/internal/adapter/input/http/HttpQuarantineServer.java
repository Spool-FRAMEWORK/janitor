package software.spool.janitor.internal.adapter.input.http;

import software.spool.core.adapter.jackson.PayloadDeserializerFactory;
import software.spool.core.adapter.jackson.RecordSerializerFactory;
import software.spool.core.exception.DeserializationException;
import software.spool.core.model.quarantine.QuarantinePageRequest;
import software.spool.core.model.quarantine.QuarantineStatus;
import software.spool.core.model.quarantine.QuarantinedRecord;
import software.spool.core.model.quarantine.Resolver;
import software.spool.core.model.vo.IdempotencyKey;
import software.spool.core.port.authentication.RequestAuthenticator;
import software.spool.core.port.http.Caller;
import software.spool.core.port.http.HttpMethod;
import software.spool.core.port.http.HttpRequest;
import software.spool.core.port.http.HttpResponse;
import software.spool.core.port.http.HttpRoute;
import software.spool.core.port.http.RouteAccess;
import software.spool.janitor.api.quarantine.QuarantinePageSizes;
import software.spool.janitor.api.quarantine.QuarantineRoles;
import software.spool.janitor.api.quarantine.QuarantineServer;
import software.spool.janitor.api.quarantine.QuarantinedEnvelopeBypasser;
import software.spool.janitor.api.quarantine.QuarantinedEnvelopeFinder;
import software.spool.janitor.api.quarantine.QuarantinedEnvelopePurger;
import software.spool.janitor.api.quarantine.QuarantinedEnvelopeRepublisher;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/**
 * The quarantine API of ADR-014 on the node's HTTP server: six routes, each one checked with its role and handed to a
 * use case. A record that is not in quarantine answers {@code 404}, one already resolved {@code 409}, and a request
 * without what it needs {@code 400}.
 */
public final class HttpQuarantineServer implements QuarantineServer {
    private static final String BASE = "/spool/quarantine";
    private static final String RECORD = BASE + "/{key}";
    private static final String KEY = "key";
    private static final String STATUS = "status";
    private static final String LIMIT = "limit";
    private static final String CURSOR = "cursor";
    private static final int OK = 200;
    private static final int BAD_REQUEST = 400;
    private static final int NOT_FOUND = 404;
    private static final int CONFLICT = 409;

    private final QuarantinedEnvelopeFinder finder;
    private final QuarantinedEnvelopeRepublisher republisher;
    private final QuarantinedEnvelopeBypasser bypasser;
    private final QuarantinedEnvelopePurger purger;
    private final RequestAuthenticator authenticator;
    private final QuarantineRoles roles;
    private final QuarantinePageSizes pageSizes;

    public HttpQuarantineServer(QuarantinedEnvelopeFinder finder, QuarantinedEnvelopeRepublisher republisher,
                                QuarantinedEnvelopeBypasser bypasser, QuarantinedEnvelopePurger purger,
                                RequestAuthenticator authenticator, QuarantineRoles roles, QuarantinePageSizes pageSizes) {
        this.finder = finder;
        this.republisher = republisher;
        this.bypasser = bypasser;
        this.purger = purger;
        this.authenticator = authenticator;
        this.roles = roles;
        this.pageSizes = pageSizes;
    }

    @Override
    public Collection<HttpRoute> routes() {
        return List.of(
                new HttpRoute(HttpMethod.GET, BASE, access(roles.read()), (request, caller) -> list(request)),
                new HttpRoute(HttpMethod.GET, RECORD, access(roles.read()), (request, caller) -> show(request)),
                new HttpRoute(HttpMethod.POST, RECORD + "/republish", access(roles.resolve()), (request, caller) ->
                        resolving(request, caller, (key, resolver) -> republisher.republish(List.of(key), resolver).stream().findFirst())),
                new HttpRoute(HttpMethod.POST, RECORD + "/amend", access(roles.resolve()), this::amend),
                new HttpRoute(HttpMethod.POST, RECORD + "/bypass", access(roles.bypass()), this::bypass),
                new HttpRoute(HttpMethod.POST, RECORD + "/purge", access(roles.resolve()), (request, caller) ->
                        resolving(request, caller, (key, resolver) -> purger.purge(List.of(key), resolver).stream().findFirst())));
    }

    private HttpResponse list(HttpRequest request) {
        Optional<QuarantineStatus> status = statusOf(request.queryParameter(STATUS).orElse(QuarantineStatus.PENDING.name()));
        if (status.isEmpty()) {
            return error(BAD_REQUEST, "status must be one of " + Arrays.stream(QuarantineStatus.values())
                    .map(Enum::name).collect(Collectors.joining(", ")));
        }
        Optional<Integer> limit = limitOf(request.queryParameter(LIMIT).orElse(String.valueOf(pageSizes.byDefault())));
        if (limit.isEmpty() || limit.get() < 1) return error(BAD_REQUEST, "limit must be a whole number of at least 1");
        if (limit.get() > pageSizes.maximum()) {
            return error(BAD_REQUEST, "limit is too big: a page has at most " + pageSizes.maximum() + " records");
        }
        QuarantinePageRequest pageRequest = new QuarantinePageRequest(status.get(), limit.get(),
                request.queryParameter(CURSOR).map(IdempotencyKey::of));
        return json(OK, QuarantinePagePayload.of(finder.list(pageRequest)));
    }

    private HttpResponse show(HttpRequest request) {
        IdempotencyKey key = keyOf(request);
        return finder.find(key)
                .map(found -> json(OK, QuarantinedRecordPayload.of(found)))
                .orElseGet(() -> notInQuarantine(key));
    }

    private HttpResponse amend(HttpRequest request, Caller caller) {
        byte[] amended = request.body();
        if (amended.length == 0) return error(BAD_REQUEST, "the amended payload is missing: send it as the body of the request");
        return resolving(request, caller, (key, resolver) -> republisher.republish(key, amended, resolver));
    }

    private HttpResponse bypass(HttpRequest request, Caller caller) {
        Optional<String> reason = reasonOf(request.body());
        if (reason.isEmpty()) {
            return error(BAD_REQUEST, "the reason is missing: letting a record through needs {\"reason\": \"...\"} in the body");
        }
        return resolving(request, caller, (key, resolver) -> bypasser.bypass(key, reason.get(), resolver));
    }

    private HttpResponse resolving(HttpRequest request, Caller caller,
                                   BiFunction<IdempotencyKey, Resolver, Optional<IdempotencyKey>> resolution) {
        IdempotencyKey key = keyOf(request);
        Optional<QuarantinedRecord> found = finder.find(key);
        if (found.isEmpty()) return notInQuarantine(key);
        if (found.get().status() != QuarantineStatus.PENDING) return alreadyResolved(found.get());
        if (resolution.apply(key, resolverOf(caller)).isEmpty()) {
            return finder.find(key).map(this::alreadyResolved).orElseGet(() -> notInQuarantine(key));
        }
        return json(OK, QuarantinedRecordPayload.of(finder.find(key).orElseThrow()));
    }

    private HttpResponse alreadyResolved(QuarantinedRecord quarantinedRecord) {
        return error(CONFLICT, quarantinedRecord.idempotencyKey().value() + " was already resolved as " + quarantinedRecord.status());
    }

    private static HttpResponse notInQuarantine(IdempotencyKey key) {
        return error(NOT_FOUND, key.value() + " is not in quarantine");
    }

    private RouteAccess access(String role) {
        return new RouteAccess.Protected(authenticator, role);
    }

    private static IdempotencyKey keyOf(HttpRequest request) {
        return IdempotencyKey.of(request.pathParameter(KEY).orElseThrow());
    }

    private static Resolver resolverOf(Caller caller) {
        return switch (caller) {
            case Caller.Identified identified -> Resolver.identified(identified.identity().subject(), identified.identity().provider());
            case Caller.Anonymous anonymous -> Resolver.unknown();
        };
    }

    private static Optional<QuarantineStatus> statusOf(String value) {
        return Arrays.stream(QuarantineStatus.values()).filter(status -> status.name().equals(value)).findFirst();
    }

    private static Optional<Integer> limitOf(String value) {
        try {
            return Optional.of(Integer.parseInt(value.strip()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> reasonOf(byte[] body) {
        if (body.length == 0) return Optional.empty();
        try {
            return Optional.ofNullable(PayloadDeserializerFactory.json().as(BypassPayload.class).deserialize(body).reason())
                    .filter(reason -> !reason.isBlank());
        } catch (DeserializationException e) {
            return Optional.empty();
        }
    }

    private static HttpResponse json(int status, Object body) {
        return HttpResponse.json(status, RecordSerializerFactory.record().serialize(body));
    }

    private static HttpResponse error(int status, String message) {
        return json(status, new ErrorPayload(message));
    }
}
