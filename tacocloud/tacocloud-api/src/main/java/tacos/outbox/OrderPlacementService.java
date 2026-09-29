package tacos.outbox;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.Timer;
import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.api.dto.OrderCreateRequest;
import tacos.data.OrderRepository;
import tacos.idempotency.IdempotencyKeys;
import tacos.idempotency.IdempotencyRecord;
import tacos.idempotency.IdempotencyService;
import tacos.idempotency.IdempotencyStatus;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventMapper;
import tacos.observability.TacoBusinessMetrics;
import tacos.web.api.CallerIdentity;
import tacos.web.api.OrderApiService;

/**
 * The only way to place an order that the kitchen will reliably see (TC-29).
 *
 * <p>Creation and outbox registration commit in the same local transaction:
 * a failure before the commit leaves zero order and zero outbox, and a
 * confirmed order always has a {@code NEW} row for the relay. The HTTP layer
 * answers after the local commit; the broker delivery happens afterwards in
 * {@link OutboxRelay}, at least once, deduplicated by the consumer (TC-30).
 */
@Service
public class OrderPlacementService {

  private final OrderApiService orders;
  private final OutboxService outbox;
  private final OrderEventMapper events;

  @Autowired(required = false)
  private TacoBusinessMetrics metrics;

  @Autowired(required = false)
  private IdempotencyService idempotency;

  @Autowired(required = false)
  private OrderRepository orderLookup;

  public OrderPlacementService(OrderApiService orders, OutboxService outbox,
                               OrderEventMapper events) {
    this.orders = orders;
    this.outbox = outbox;
    this.events = events;
  }

  @Transactional
  public Mono<TacoOrder> placeOrder(OrderCreateRequest request, CallerIdentity caller,
                                    String correlationId) {
    return placeOrder(request, caller, correlationId, null);
  }

  /**
   * Idempotent placement (TC-34): the {@code Idempotency-Key} header scopes
   * by user. Same key plus same purchase replays the stored order without
   * reserving or publishing twice; same key plus another purchase is a 409.
   * Without a key (or without the supporting beans in unit tests) it behaves
   * exactly like the plain creation above.
   */
  @Transactional
  public Mono<TacoOrder> placeOrder(OrderCreateRequest request, CallerIdentity caller,
                                    String correlationId, String idempotencyKeyHeader) {
    String headerKey;
    try {
      headerKey = IdempotencyKeys.normalizeHeader(idempotencyKeyHeader);
    } catch (tacos.idempotency.InvalidIdempotencyKeyException e) {
      return Mono.error(e);
    }
    String bodyKey = request.getIdempotencyKey() == null
        ? null : request.getIdempotencyKey().trim();
    if (bodyKey != null && bodyKey.isEmpty()) {
      bodyKey = null;
    }
    String effectiveKey = headerKey != null ? headerKey : bodyKey;
    if (effectiveKey == null || idempotency == null || orderLookup == null) {
      return createAndPublish(request, caller, correlationId);
    }
    String hash = IdempotencyKeys.canonicalHash(request);
    String userId = caller.getUserId();
    return idempotency.reserve(effectiveKey, userId, hash)
        .flatMap(record -> {
          if (record.getStatus() == IdempotencyStatus.COMPLETED && record.getOrderId() != null) {
            return orderLookup.findById(record.getOrderId())
                .switchIfEmpty(Mono.defer(() ->
                    createAndPublish(request, caller, correlationId)
                        .flatMap(fresh -> idempotency.complete(record, fresh.getId())
                            .thenReturn(fresh))));
          }
          return createAndPublish(request, caller, correlationId)
              .flatMap(fresh -> idempotency.complete(record, fresh.getId())
                  .thenReturn(fresh))
              .onErrorResume(e -> idempotency.fail(record).then(Mono.error(e)));
        });
  }

  private Mono<TacoOrder> createAndPublish(OrderCreateRequest request, CallerIdentity caller,
                                          String correlationId) {
    Timer.Sample sample = metrics == null ? null : metrics.startPlacement();
    return orders.createOrder(request, caller)
        .flatMap(saved -> {
          OrderEvent event = events.toCreated(saved, correlationId);
          return outbox.append(event).thenReturn(saved);
        })
        .doOnSuccess(saved -> {
          if (metrics != null) {
            metrics.orderCreated("api");
            metrics.stopPlacement(sample, "created");
            if (saved.getCouponCode() != null) {
              metrics.couponApplied();
            }
          }
        })
        .doOnError(e -> {
          if (metrics != null) {
            if (e instanceof tacos.inventory.InsufficientStockException) {
              metrics.stockRejected();
            }
            metrics.orderFailed("api");
            metrics.stopPlacement(sample, "failed");
          }
        });
  }
}
