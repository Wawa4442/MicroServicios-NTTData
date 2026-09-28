package tacos.consumer;

import java.time.Instant;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import reactor.core.publisher.Mono;
import tacos.OrderStatus;
import tacos.data.OrderRepository;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventType;
import tacos.web.api.CallerIdentity;
import tacos.workflow.InvalidOrderStatusException;
import tacos.workflow.OrderWorkflowService;

/**
 * Idempotent kitchen consumer (TC-30).
 *
 * <p>The contract: the same {@code eventId} delivered twice changes the
 * business state once. Deduplication runs on {@code eventId} (never on
 * {@code orderId}); the business effect and the processed-mark commit in the
 * same local transaction, so a crash between them either loses both (and the
 * redelivery retries) or keeps both (and the redelivery is a no-op). Only
 * transient errors retry, with a bounded count; anything permanent, unknown
 * or exhausted goes to the dead-letter collection with cause and correlation
 * but without sensitive data.
 *
 * <p>Portable across brokers by design: the only broker-specific part is who
 * calls {@link #consume}, and that caller must not acknowledge before this
 * {@code Mono} completes.
 */
@Service
public class OrderEventConsumer {

  private final ProcessedEventRepository processed;
  private final DeadLetterRepository deadLetters;
  private final OrderRepository orders;
  private final OrderWorkflowService workflow;
  private final ConsumerProperties props;
  private final ConsumerMetrics metrics;

  public OrderEventConsumer(ProcessedEventRepository processed,
                            DeadLetterRepository deadLetters,
                            OrderRepository orders,
                            OrderWorkflowService workflow,
                            ConsumerProperties props,
                            ConsumerMetrics metrics) {
    this.processed = processed;
    this.deadLetters = deadLetters;
    this.orders = orders;
    this.workflow = workflow;
    this.props = props;
    this.metrics = metrics;
  }

  /**
   * Processes one delivery. A duplicate {@code eventId} is acknowledged
   * without repeating the business effect.
   */
  public Mono<Void> consume(OrderEvent event) {
    return consume(event, 0);
  }

  Mono<Void> consume(OrderEvent event, int attempt) {
    if (event == null || event.getEventId() == null || event.getEventType() == null) {
      return park(event, "malformed_event", "Event, eventId or eventType is missing", attempt);
    }
    if (!OrderEvent.CURRENT_VERSION.equals(event.getVersion())) {
      return park(event, "unsupported_version",
          "Event version '" + event.getVersion() + "' is not supported", attempt);
    }
    return processed.existsByEventId(event.getEventId())
        .flatMap(seen -> {
          if (seen) {
            metrics.duplicate();
            return Mono.<Void>empty();
          }
          return applyOnce(event)
              .onErrorResume(DuplicateKeyException.class, dup ->
                  // Lost the insert race with a concurrent delivery of the
                  // same event: the winner owns the effect, we just ack.
                  Mono.fromRunnable(metrics::duplicate).then())
              .onErrorResume(e -> handleFailure(event, e, attempt));
        });
  }

  /**
   * Business effect plus processed-mark in one transaction (TC-30).
   */
  @Transactional
  public Mono<Void> applyOnce(OrderEvent event) {
    return applyEffect(event)
        .then(markProcessed(event))
        .doOnSuccess(ignored -> metrics.processed());
  }

  private Mono<Void> applyEffect(OrderEvent event) {
    if (event.getEventType() == OrderEventType.ORDER_CREATED) {
      // Notification only: the order was committed with the outbox row, so
      // the kitchen queue already lists it. Verifying existence keeps a
      // poison event (order that never existed) from being silently acked.
      String orderId = event.getPayload() == null ? null
          : event.getPayload().getOrderId();
      if (orderId == null) {
        return Mono.error(new InvalidOrderStatusException(
            "ORDER_CREATED without orderId cannot be applied."));
      }
      return orders.findById(orderId)
          .switchIfEmpty(Mono.error(new InvalidOrderStatusException(
              "Order " + orderId + " does not exist.")))
          .then();
    }
    if (event.getEventType() == OrderEventType.STATUS_CHANGED
        || event.getEventType() == OrderEventType.ORDER_CANCELLED) {
      String orderId = event.getPayload() == null ? null
          : event.getPayload().getOrderId();
      String status = event.getPayload() == null ? null
          : event.getPayload().getStatus();
      OrderStatus target;
      try {
        target = OrderStatus.valueOf(status);
      } catch (Exception e) {
        return Mono.error(new InvalidOrderStatusException(
            "Unknown status '" + status + "' in event " + event.getEventId()));
      }
      // Reconciliation through the same matrix: an already-applied event is
      // idempotent (same status -> no-op), an illegal jump is permanent.
      CallerIdentity system = CallerIdentity.admin("system-consumer");
      return workflow.transition(orderId, target, system, "EVENT",
          "Reconciled from event " + event.getEventId()).then();
    }
    return Mono.error(new InvalidOrderStatusException(
        "Unknown event type '" + event.getEventType() + "'."));
  }

  private Mono<Void> markProcessed(OrderEvent event) {
    ProcessedEvent row = new ProcessedEvent();
    row.setEventId(event.getEventId());
    row.setOrderId(event.getPayload() == null ? null
        : event.getPayload().getOrderId());
    row.setEventType(event.getEventType().name());
    row.setResult("OK");
    row.setProcessedAt(Instant.now());
    return processed.save(row).then();
  }

  private Mono<Void> handleFailure(OrderEvent event, Throwable error, int attempt) {
    if (isTransient(error) && attempt < props.getMaxRetries()) {
      metrics.retried();
      return Mono.error(new TransientConsumerException(
          "Transient failure on event " + event.getEventId()
          + " (attempt " + (attempt + 1) + " of " + props.getMaxRetries() + ")",
          error));
    }
    String cause = isTransient(error) ? "retry_exhausted" : "permanent_error";
    return park(event, cause,
        error == null ? "processing failed" : error.toString(), attempt);
  }

  static boolean isTransient(Throwable error) {
    if (error instanceof TransientConsumerException) {
      return true;
    }
    if (error instanceof OptimisticLockingFailureException) {
      return true;
    }
    if (error instanceof java.util.concurrent.TimeoutException) {
      return true;
    }
    if (error instanceof org.springframework.dao.TransientDataAccessException) {
      return true;
    }
    if (error instanceof org.springframework.dao.DataAccessResourceFailureException) {
      return true;
    }
    return false;
  }

  private Mono<Void> park(OrderEvent event, String cause, String error, int attempt) {
    DeadLetter letter = new DeadLetter();
    letter.setEventId(event == null || event.getEventId() == null
        ? "missing-" + System.nanoTime() : event.getEventId());
    letter.setDestination(props.getDeadLetterDestination());
    letter.setEventType(event == null || event.getEventType() == null ? null
        : event.getEventType().name());
    letter.setEventVersion(event == null ? null : event.getVersion());
    letter.setOrderId(event == null || event.getPayload() == null ? null
        : event.getPayload().getOrderId());
    letter.setCorrelationId(event == null ? null : event.getCorrelationId());
    letter.setCause(cause);
    letter.setError(error == null ? null
        : (error.length() > 500 ? error.substring(0, 500) : error));
    letter.setAttempts(attempt);
    letter.setFailedAt(Instant.now());
    return deadLetters.save(letter)
        .doOnSuccess(ignored -> metrics.deadLettered())
        .then();
  }

  /**
   * Controlled replay (TC-30): re-drives a parked event after the operator
   * fixed the cause. Success removes the letter; a repeated effect stays a
   * no-op thanks to the {@code eventId} dedup.
   */
  public Mono<Void> replay(String eventId, java.util.function.Function<String, Mono<OrderEvent>> loader) {
    return deadLetters.findByEventId(eventId)
        .switchIfEmpty(Mono.error(new InvalidOrderStatusException(
            "No dead letter for event " + eventId)))
        .flatMap(letter -> loader.apply(eventId)
            .flatMap(event -> consume(event)
                .onErrorResume(TransientConsumerException.class, e ->
                    Mono.error(new IllegalStateException(
                        "Replay of " + eventId + " is still failing transiently; "
                        + "fix the cause and try again."))))
            .then(deadLetters.delete(letter).then()));
  }
}
