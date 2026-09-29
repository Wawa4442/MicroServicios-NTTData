package tacos.idempotency;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface IdempotencyRecordRepository
    extends ReactiveCrudRepository<IdempotencyRecord, String> {

  Mono<IdempotencyRecord> findByKeyAndUserId(String key, String userId);

  Flux<IdempotencyRecord> findByExpiresAtBefore(java.time.Instant now);
}
