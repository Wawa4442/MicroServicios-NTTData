package tacos.idempotency;

import java.time.Clock;
import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;

/**
 * Remembers one HTTP attempt so a double click retries safely (TC-34).
 *
 * <p>Same key plus same purchase replays the stored order; same key plus a
 * different purchase is a 409; two arrivals at once collapse on the unique
 * id, so only one order is ever created. A {@code PENDING} row older than a
 * few minutes is treated as abandoned (the owner crashed before committing)
 * and may be reclaimed; a fresh {@code PENDING} answers 409
 * {@code idempotency_in_progress} instead of inventing a second order.
 * Records expire after the configured TTL and are purged lazily.
 */
@Service
public class IdempotencyService {

  private final IdempotencyRecordRepository repository;
  private final IdempotencyProperties props;
  private final Clock clock;

  public IdempotencyService(IdempotencyRecordRepository repository,
                            IdempotencyProperties props, Clock clock) {
    this.repository = repository;
    this.props = props;
    this.clock = clock;
  }

  public Mono<IdempotencyRecord> reserve(String key, String userId, String requestHash) {
    Instant now = clock.instant();
    IdempotencyRecord row = new IdempotencyRecord();
    row.setId(IdempotencyKeys.recordId(userId, key));
    row.setKey(key);
    row.setUserId(userId == null ? "anonymous" : userId);
    row.setRequestHash(requestHash);
    row.setStatus(IdempotencyStatus.PENDING);
    row.setCreatedAt(now);
    row.setUpdatedAt(now);
    row.setExpiresAt(now.plus(props.getTtl()));
    return repository.save(row)
        .onErrorResume(DuplicateKeyException.class, dup -> repository.findById(row.getId())
            .switchIfEmpty(Mono.error(new IdempotencyConflictException(
                "Idempotency key is already in use.")))
            .flatMap(existing -> {
              if (!requestHash.equals(existing.getRequestHash())) {
                return Mono.error(new IdempotencyConflictException(
                    "Idempotency-Key was already used with a different payload."));
              }
              if (existing.getStatus() == IdempotencyStatus.COMPLETED) {
                return Mono.just(existing);
              }
              if (existing.getStatus() == IdempotencyStatus.FAILED) {
                existing.setStatus(IdempotencyStatus.PENDING);
                existing.setRequestHash(requestHash);
                existing.setUpdatedAt(now);
                existing.setExpiresAt(now.plus(props.getTtl()));
                return repository.save(existing);
              }
              // PENDING: fresh means someone else is committing right now.
              Instant stuckAfter = existing.getCreatedAt().plusSeconds(300);
              if (now.isAfter(stuckAfter)) {
                existing.setRequestHash(requestHash);
                existing.setUpdatedAt(now);
                return repository.save(existing);
              }
              return Mono.error(new IdempotencyConflictException(
                  "An order with this Idempotency-Key is already in progress."));
            }));
  }

  public Mono<IdempotencyRecord> complete(IdempotencyRecord row, String orderId) {
    row.setOrderId(orderId);
    row.setStatus(IdempotencyStatus.COMPLETED);
    row.setUpdatedAt(clock.instant());
    return repository.save(row);
  }

  public Mono<IdempotencyRecord> fail(IdempotencyRecord row) {
    row.setStatus(IdempotencyStatus.FAILED);
    row.setUpdatedAt(clock.instant());
    return repository.save(row);
  }
}
