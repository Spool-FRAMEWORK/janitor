package software.spool.janitor.internal.adapter.input.http;

/**
 * The body of a bypass: why the record is valid although a validator rejected it.
 */
record BypassPayload(String reason) {
}
