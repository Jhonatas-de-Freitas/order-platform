package io.github.jhonatasdefreitas.payments.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class ProcessedEvents {

    private final JdbcClient jdbc;

    public ProcessedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Must run in the transaction that applies the event, so both commit or roll back together.
     * A concurrent delivery of the same event waits on the primary key and then sees the conflict.
     *
     * @return false when the event had already been processed
     */
    public boolean markProcessed(UUID eventId) {
        return jdbc.sql("insert into processed_events (event_id) values (?) on conflict do nothing")
                .param(eventId)
                .update() == 1;
    }
}
