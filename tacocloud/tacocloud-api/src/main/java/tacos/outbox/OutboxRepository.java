package tacos.outbox;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OutboxRepository extends ReactiveCrudRepository<OutboxEvent, String> {

  Mono<OutboxEvent> findByEventId(String eventId);

  Flux<OutboxEvent> findByStatus(OutboxStatus status);
}
