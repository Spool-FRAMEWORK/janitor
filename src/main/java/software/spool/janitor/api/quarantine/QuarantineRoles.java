package software.spool.janitor.api.quarantine;

import java.util.Objects;

/**
 * The role a caller needs for each kind of operation on the quarantine: reading it, resolving a record, and letting
 * one through, which has its own role because it lets in data a rule rejects.
 */
public record QuarantineRoles(String read, String resolve, String bypass) {
    public QuarantineRoles {
        Objects.requireNonNull(read, "read is required");
        Objects.requireNonNull(resolve, "resolve is required");
        Objects.requireNonNull(bypass, "bypass is required");
    }
}
