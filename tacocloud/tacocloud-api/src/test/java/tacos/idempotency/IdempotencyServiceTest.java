package tacos.idempotency;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * TC-34 reserve/replay rules without a real Mongo.
 */
public class IdempotencyServiceTest {

  private IdempotencyRecordRepository repository;
  private IdempotencyService service;

  @BeforeEach
  public void setup() {
    repository = Mockito.mock(IdempotencyRecordRepository.class);
    IdempotencyProperties props = new IdempotencyProperties();
    Clock clock = Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneId.of("UTC"));
    service = new IdempotencyService(repository, props, clock);
  }

  @Test
  public void tc34_firstUse_createsPending() {
    when(repository.save(any(IdempotencyRecord.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StepVerifier.create(service.reserve("key-12345678", "alice", "hash1"))
        .expectNextMatches(r -> r.getStatus() == IdempotencyStatus.PENDING
            && "alice:key-12345678".equals(r.getId()))
        .verifyComplete();
  }

  @Test
  public void tc34_sameKeySameHash_replaysCompleted() {
    IdempotencyRecord existing = new IdempotencyRecord();
    existing.setId("alice:key-12345678");
    existing.setRequestHash("hash1");
    existing.setStatus(IdempotencyStatus.COMPLETED);
    existing.setOrderId("order-1");
    when(repository.save(any(IdempotencyRecord.class)))
        .thenReturn(Mono.error(new DuplicateKeyException("dup")));
    when(repository.findById("alice:key-12345678"))
        .thenReturn(Mono.just(existing));

    StepVerifier.create(service.reserve("key-12345678", "alice", "hash1"))
        .expectNextMatches(r -> "order-1".equals(r.getOrderId()))
        .verifyComplete();
  }

  @Test
  public void tc34_sameKeyDifferentPayload_isConflict() {
    IdempotencyRecord existing = new IdempotencyRecord();
    existing.setId("alice:key-12345678");
    existing.setRequestHash("hash1");
    existing.setStatus(IdempotencyStatus.COMPLETED);
    when(repository.save(any(IdempotencyRecord.class)))
        .thenReturn(Mono.error(new DuplicateKeyException("dup")));
    when(repository.findById("alice:key-12345678"))
        .thenReturn(Mono.just(existing));

    StepVerifier.create(service.reserve("key-12345678", "alice", "hash2"))
        .expectError(IdempotencyConflictException.class)
        .verify();
  }

  @Test
  public void tc34_usersDoNotCollideOnSameKey() {
    when(repository.save(any(IdempotencyRecord.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StepVerifier.create(service.reserve("shared-key-123", "alice", "hash1"))
        .expectNextMatches(r -> "alice:shared-key-123".equals(r.getId()))
        .verifyComplete();
    StepVerifier.create(service.reserve("shared-key-123", "bob", "hash1"))
        .expectNextMatches(r -> "bob:shared-key-123".equals(r.getId()))
        .verifyComplete();
  }
}
