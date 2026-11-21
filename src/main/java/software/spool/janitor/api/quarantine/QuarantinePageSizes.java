package software.spool.janitor.api.quarantine;

/**
 * How many records a page of the quarantine has when the caller does not say, and how many it can have at most.
 */
public record QuarantinePageSizes(int byDefault, int maximum) {
    public QuarantinePageSizes {
        if (byDefault < 1) throw new IllegalArgumentException("A page needs at least one record by default, got " + byDefault);
        if (maximum < byDefault) {
            throw new IllegalArgumentException("The largest page (" + maximum + ") cannot be smaller than the default one (" + byDefault + ")");
        }
    }
}
