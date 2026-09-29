package tacos.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.TacoLineRequest;
import tacos.data.OrderRepository;
import tacos.idempotency.IdempotencyProperties;
import tacos.idempotency.IdempotencyRecord;
import tacos.idempotency.IdempotencyRecordRepository;
import tacos.idempotency.IdempotencyService;
import tacos.idempotency.IdempotencyStatus;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventMapper;
import tacos.web.api.CallerIdentity;
import tacos.web.api.OrderApiService;

/**
 * TC-34 through placement: a retry must not reserve or publish twice.
 */
public class IdempotentPlacementTest {

  private OrderApiService orders;
  private OutboxService outbox;
  private OrderPlacementService placement;
  private IdempotencyRecordRepository idempotencyRepo;
  private OrderRepository orderLookup;

  @BeforeEach
  public void setup() {
    orders = Mockito.mock(OrderApiService.class);
    outbox = Mockito.mock(OutboxService.class);
    orderLookup = Mockito.mock(OrderRepository.class);
    idempotencyRepo = Mockito.mock(IdempotencyRecordRepository.class);
    IdempotencyService idempotency = new IdempotencyService(
        idempotencyRepo, new IdempotencyProperties(),
        Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneId.of("UTC")));
    placement = new OrderPlacementService(orders, outbox, new OrderEventMapper());
    ReflectionTestUtils.setField(placement, "idempotency", idempotency);
    ReflectionTestUtils.setField(placement, "orderLookup", orderLookup);
  }

  private static OrderCreateRequest purchase() {
    OrderCreateRequest request = new OrderCreateRequest();
    request.setDeliveryName("Alice");
    request.setDeliveryStreet("Main 1");
    request.setDeliveryCity("Austin");
    request.setDeliveryState("TX");
    request.setDeliveryZip("78701");
    TacoLineRequest line = new TacoLineRequest();
    line.setName("Classic");
    line.setIngredientIds(List.of("FLTO"));
    line.setQuantity(1);
    request.setTacos(List.of(line));
    return request;
  }

  @Test
  public void tc34_sequentialRetry_returnsSameOrderWithoutRecreating() {
    OrderCreateRequest request = purchase();
    String hash = tacos.idempotency.IdempotencyKeys.canonicalHash(request);
    TacoOrder saved = new TacoOrder();
    saved.setId("order-1");
    when(orders.createOrder(any(), any())).thenReturn(Mono.just(saved));
    when(outbox.append(any(OrderEvent.class)))
        .thenAnswer(inv -> Mono.just(new OutboxEvent()));

    IdempotencyRecord pending = new IdempotencyRecord();
    pending.setId("u1:key-12345678");
    pending.setStatus(IdempotencyStatus.PENDING);
    pending.setRequestHash(hash);
    IdempotencyRecord completed = new IdempotencyRecord();
    completed.setId("u1:key-12345678");
    completed.setStatus(IdempotencyStatus.COMPLETED);
    completed.setRequestHash(hash);
    completed.setOrderId("order-1");
    when(idempotencyRepo.save(any(IdempotencyRecord.class)))
        .thenReturn(Mono.just(pending))
        .thenReturn(Mono.just(completed));
    when(orderLookup.findById("order-1")).thenReturn(Mono.just(saved));

    // First attempt creates.
    StepVerifier.create(placement.placeOrder(request,
            CallerIdentity.user("u1"), "corr-1", "key-12345678"))
        .expectNextMatches(o -> "order-1".equals(o.getId()))
        .verifyComplete();

    // Second attempt with the same key+payload replays without creating again.
    when(idempotencyRepo.save(any(IdempotencyRecord.class)))
        .thenReturn(Mono.error(new org.springframework.dao.DuplicateKeyException("dup")));
    when(idempotencyRepo.findById("u1:key-12345678"))
        .thenReturn(Mono.just(completed));

    StepVerifier.create(placement.placeOrder(request,
            CallerIdentity.user("u1"), "corr-1", "key-12345678"))
        .expectNextMatches(o -> "order-1".equals(o.getId()))
        .verifyComplete();

    verify(orders, times(1)).createOrder(any(), any());
    verify(outbox, times(1)).append(any(OrderEvent.class));
  }

  @Test
  public void tc34_sameKeyDifferentPayload_is409WithoutCreating() {
    OrderCreateRequest first = purchase();
    OrderCreateRequest second = purchase();
    second.setDeliveryZip("90210");

    IdempotencyRecord existing = new IdempotencyRecord();
    existing.setId("u1:key-12345678");
    existing.setRequestHash("hash-first");
    existing.setStatus(IdempotencyStatus.COMPLETED);
    existing.setOrderId("order-1");
    when(idempotencyRepo.save(any(IdempotencyRecord.class)))
        .thenReturn(Mono.error(new org.springframework.dao.DuplicateKeyException("dup")));
    when(idempotencyRepo.findById("u1:key-12345678"))
        .thenAnswer(inv -> {
          // First call in reserve() compares hashes: force a mismatch by
          // returning a stored hash that cannot equal the fresh one.
          IdempotencyRecord row = new IdempotencyRecord();
          row.setId("u1:key-12345678");
          row.setRequestHash("definitely-different");
          row.setStatus(IdempotencyStatus.COMPLETED);
          row.setOrderId("order-1");
          return Mono.just(row);
        });

    StepVerifier.create(placement.placeOrder(second,
            CallerIdentity.user("u1"), "corr-1", "key-12345678"))
        .expectErrorMatches(e ->
            e instanceof tacos.idempotency.IdempotencyConflictException)
        .verify();

    verify(orders, never()).createOrder(any(), any());
    verify(outbox, never()).append(any(OrderEvent.class));
  }

  @Test
  public void tc34_badKeyFormat_is400WithoutCreating() {
    StepVerifier.create(placement.placeOrder(purchase(),
            CallerIdentity.user("u1"), "corr-1", "bad"))
        .expectErrorMatches(e ->
            e instanceof tacos.idempotency.InvalidIdempotencyKeyException)
        .verify();

    verify(orders, never()).createOrder(any(), any());
  }
}
