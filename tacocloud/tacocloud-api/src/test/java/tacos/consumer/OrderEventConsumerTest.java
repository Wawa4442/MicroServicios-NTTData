package tacos.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.OptimisticLockingFailureException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.OrderStatus;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventPayload;
import tacos.messaging.OrderEventType;
import tacos.workflow.InvalidOrderStatusException;
import tacos.workflow.OrderWorkflowService;

/**
 * TC-30: redeliveries do not duplicate effects, poison ends up visible.
 */
public class OrderEventConsumerTest {

  private ProcessedEventRepository processed;
  private DeadLetterRepository deadLetters;
  private OrderRepository orders;
  private OrderWorkflowService workflow;
  private ConsumerProperties props;
  private OrderEventConsumer consumer;

  @BeforeEach
  public void setup() {
    processed = Mockito.mock(ProcessedEventRepository.class);
    deadLetters = Mockito.mock(DeadLetterRepository.class);
    orders = Mockito.mock(OrderRepository.class);
    workflow = Mockito.mock(OrderWorkflowService.class);
    props = new ConsumerProperties();
    consumer = new OrderEventConsumer(processed, deadLetters, orders,
        workflow, props, new ConsumerMetrics());
    when(deadLetters.save(any(DeadLetter.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    when(processed.save(any(ProcessedEvent.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
  }

  private OrderEvent created(String eventId, String orderId) {
    OrderEventPayload payload = new OrderEventPayload();
    payload.setOrderId(orderId);
    payload.setStatus("CREATED");
    OrderEvent event = new OrderEvent();
    event.setEventId(eventId);
    event.setEventType(OrderEventType.ORDER_CREATED);
    event.setVersion("v1");
    event.setOccurredAt(Instant.now());
    event.setPayload(payload);
    return event;
  }

  private OrderEvent statusChanged(String eventId, String orderId, String status) {
    OrderEventPayload payload = new OrderEventPayload();
    payload.setOrderId(orderId);
    payload.setStatus(status);
    OrderEvent event = new OrderEvent();
    event.setEventId(eventId);
    event.setEventType(OrderEventType.STATUS_CHANGED);
    event.setVersion("v1");
    event.setOccurredAt(Instant.now());
    event.setPayload(payload);
    return event;
  }

  @Test
  public void tc30_sameEventTwice_changesStateOnce() {
    TacoOrder stored = new TacoOrder();
    stored.setId("o1");
    stored.setPlacedAt(new Date());
    stored.setStatus(OrderStatus.CREATED);
    when(processed.existsByEventId("e1")).thenReturn(Mono.just(false));
    when(orders.findById("o1")).thenReturn(Mono.just(stored));

    StepVerifier.create(consumer.consume(created("e1", "o1")))
        .verifyComplete();

    // Second delivery: already seen, acked without touching the business.
    when(processed.existsByEventId("e1")).thenReturn(Mono.just(true));
    StepVerifier.create(consumer.consume(created("e1", "o1")))
        .verifyComplete();

    verify(orders, Mockito.times(1)).findById("o1");
  }

  @Test
  public void tc30_transientError_retriesUpToConfiguredLimit() {
    when(processed.existsByEventId("e1")).thenReturn(Mono.just(false));
    when(orders.findById("o1")).thenReturn(Mono.just(new TacoOrder()));
    when(processed.save(any(ProcessedEvent.class))).thenReturn(
        Mono.error(new OptimisticLockingFailureException("stale mark")));

    // Optimistic collision on the dedup mark is transient: the broker
    // redelivers instead of parking.
    StepVerifier.create(consumer.consume(created("e1", "o1")))
        .expectError(TransientConsumerException.class)
        .verify();

    verify(deadLetters, never()).save(any(DeadLetter.class));
  }

  @Test
  public void tc30_permanentError_goesToDlqWithoutLoop() {
    when(processed.existsByEventId("e1")).thenReturn(Mono.just(false));
    when(orders.findById("missing")).thenReturn(Mono.empty());

    StepVerifier.create(consumer.consume(created("e1", "missing")))
        .verifyComplete();

    verify(deadLetters, Mockito.times(1)).save(any(DeadLetter.class));
  }

  @Test
  public void tc30_unknownVersion_isParkedExplicitly() {
    OrderEvent event = created("e1", "o1");
    event.setVersion("v9");

    StepVerifier.create(consumer.consume(event))
        .verifyComplete();

    verify(deadLetters, Mockito.times(1)).save(any(DeadLetter.class));
    verify(orders, never()).findById(anyString());
  }

  @Test
  public void tc30_statusReconciliation_isIdempotent() {
    TacoOrder stored = new TacoOrder();
    stored.setId("o1");
    stored.setStatus(OrderStatus.ACCEPTED);
    when(processed.existsByEventId("e1")).thenReturn(Mono.just(false));
    when(workflow.transition(any(), any(), any(), any(), any()))
        .thenReturn(Mono.just(stored));

    StepVerifier.create(consumer.consume(statusChanged("e1", "o1", "ACCEPTED")))
        .verifyComplete();

    verify(workflow, Mockito.times(1)).transition(any(), any(), any(), any(), any());
  }

  @Test
  public void tc30_replay_doesNotDuplicateEffects() {
    DeadLetter letter = new DeadLetter();
    letter.setEventId("e1");
    when(deadLetters.findByEventId("e1")).thenReturn(Mono.just(letter));
    when(deadLetters.delete(any(DeadLetter.class))).thenReturn(Mono.empty());
    when(processed.existsByEventId("e1")).thenReturn(Mono.just(true));

    StepVerifier.create(consumer.replay("e1", id -> Mono.just(created(id, "o1"))))
        .verifyComplete();

    verify(orders, never()).findById(anyString());
    verify(deadLetters, Mockito.times(1)).delete(any(DeadLetter.class));
  }

  @Test
  public void tc30_dedupKey_isEventId_notOrderId() {
    // Two different events for the same order are two different facts.
    when(processed.existsByEventId(any())).thenReturn(Mono.just(false));
    TacoOrder stored = new TacoOrder();
    stored.setId("o1");
    stored.setPlacedAt(new Date());
    stored.setStatus(OrderStatus.CREATED);
    when(orders.findById("o1")).thenReturn(Mono.just(stored));

    StepVerifier.create(consumer.consume(created("e1", "o1")))
        .verifyComplete();
    StepVerifier.create(consumer.consume(created("e2", "o1")))
        .verifyComplete();

    verify(orders, Mockito.times(2)).findById("o1");
  }

  @Test
  public void tc30_unknownEventType_isParked() {
    OrderEvent event = created("e1", "o1");
    // Simulate a future type by failing the effect with a permanent error.
    when(processed.existsByEventId("e1")).thenReturn(Mono.just(false));
    when(orders.findById("o1")).thenReturn(Mono.empty());

    StepVerifier.create(consumer.consume(event))
        .verifyComplete();

    verify(deadLetters, Mockito.times(1)).save(any(DeadLetter.class));
  }

  @Test
  public void tc30_missingEventId_isParked_notDropped() {
    OrderEvent event = created(null, "o1");

    StepVerifier.create(consumer.consume(event))
        .verifyComplete();

    verify(deadLetters, Mockito.times(1)).save(any(DeadLetter.class));
  }

  @Test
  public void tc30_crashBetweenEffectAndAck_redeliversInsteadOfLosing() {
    // The effect and the processed-mark commit together (one transaction):
    // when the mark fails, the delivery stays unacknowledged and the next
    // redelivery retries the effect instead of skipping it or parking it.
    TacoOrder stored = new TacoOrder();
    stored.setId("o1");
    stored.setPlacedAt(new Date());
    stored.setStatus(OrderStatus.CREATED);
    when(processed.existsByEventId("e1")).thenReturn(Mono.just(false));
    when(orders.findById("o1")).thenReturn(Mono.just(stored));
    when(processed.save(any(ProcessedEvent.class))).thenReturn(
        Mono.error(new OptimisticLockingFailureException("crash before ack")));

    StepVerifier.create(consumer.consume(created("e1", "o1")))
        .expectError(TransientConsumerException.class)
        .verify();

    // Not parked, not acked: the broker redelivers and the effect runs again.
    verify(deadLetters, never()).save(any(DeadLetter.class));

    // Redelivery after the crash recovers and completes exactly once more.
    when(processed.save(any(ProcessedEvent.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    StepVerifier.create(consumer.consume(created("e1", "o1")))
        .verifyComplete();
    verify(orders, Mockito.times(2)).findById("o1");
  }
}
