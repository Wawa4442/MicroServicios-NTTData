package tacos.consumer;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Mono;

public interface ProcessedEventRepository
    extends ReactiveCrudRepository<ProcessedEvent, String> {

  Mono<ProcessedEvent> findByEventId(String eventId);

  Mono<Boolean> existsByEventId(String eventId);
}
