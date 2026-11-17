package software.spool.janitor.internal.control;

/**
 * One round of the janitor over the events received since the previous one.
 */
public interface JanitorCycle {
    /**
     * @param events the events received since the previous round
     * @return the events the round could not apply to the inbox, to be tried again in the next one
     */
    EventsDTO run(EventsDTO events);
}
