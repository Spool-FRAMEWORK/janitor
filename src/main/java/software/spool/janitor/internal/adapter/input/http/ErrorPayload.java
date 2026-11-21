package software.spool.janitor.internal.adapter.input.http;

/**
 * Why a request to the quarantine API was not done.
 */
record ErrorPayload(String error) {
}
