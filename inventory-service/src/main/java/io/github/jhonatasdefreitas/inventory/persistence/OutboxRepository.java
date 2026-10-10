package io.github.jhonatasdefreitas.inventory.persistence;

import io.github.jhonatasdefreitas.inventory.messaging.OutboxEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    // SKIP LOCKED lets a second instance take the next rows instead of waiting for this batch.
    @Query(value = """
            select * from outbox
            where published_at is null
            order by id
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> lockPending(int limit);

    long countByPublishedAtIsNull();
}
