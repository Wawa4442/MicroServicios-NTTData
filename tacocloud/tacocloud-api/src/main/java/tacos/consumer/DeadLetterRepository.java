package tacos.consumer;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface DeadLetterRepository
    extends ReactiveCrudRepository<DeadLetter, String> {

  Mono<DeadLetter> findByEventId(String eventId);

  Flux<DeadLetter> findAllByOrderByFailedAtDesc();
}
