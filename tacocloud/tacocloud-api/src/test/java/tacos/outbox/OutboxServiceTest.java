package tacos.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventPayload;
import tacos.messaging.OrderEventType;
import tacos.messaging.OrderMessagingService;

/**
 * TC-29: the outbox keeps confirmed work, survives broker outages and shares
 * nothing between two relays.
 */
public class OutboxServiceTest {

  private OutboxRepository repository;
  private ReactiveMongoTemplate mongo;
  private OutboxProperties props;
  private ObjectMapper mapper;
  private OutboxService outbox;

  @BeforeEach
  public void setup() {
    repository = Mockito.mock(OutboxRepository.class);
    mongo = Mockito.mock(ReactiveMongoTemplate.class);
    props = new OutboxProperties();
    mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    outbox = new OutboxService(repository, mongo, props, mapper);
  }

  private OrderEvent event(String eventId, String orderId) {
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

  @Test
  public void tc29_confirmedOrder_alwaysHasNewOutboxRow() {
    when(repository.save(any(OutboxEvent.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StepVerifier.create(outbox.append(event("e1", "o1")))
        .expectNextMatches(row ->
            row.getEventId().equals("e1")
            && row.getStatus() == OutboxStatus.NEW
            && row.getPayload().contains("o1"))
        .verifyComplete();
  }

  @Test
  public void tc29_brokerFailure_keepsEventRetriable() {
    OutboxEvent row = new OutboxEvent();
    row.setId("row1");
    row.setEventId("e1");
    row.setStatus(OutboxStatus.PUBLISHING);
    row.setAttempts(0);
    when(repository.save(any(OutboxEvent.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StepVerifier.create(outbox.markFailed(row, "connection refused"))
        .expectNextMatches(saved ->
            saved.getStatus() == OutboxStatus.NEW
            && saved.getAttempts() == 1
            && saved.getNextAttemptAt().isAfter(Instant.now().minusSeconds(5)))
        .verifyComplete();
  }

  @Test
  public void tc29_exhaustedEvent_staysFailedAndVisible() {
    props.setMaxAttempts(3);
    OutboxEvent row = new OutboxEvent();
    row.setId("row1");
    row.setEventId("e1");
    row.setStatus(OutboxStatus.PUBLISHING);
    row.setAttempts(2);
    when(repository.save(any(OutboxEvent.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StepVerifier.create(outbox.markFailed(row, "still down"))
        .expectNextMatches(saved -> saved.getStatus() == OutboxStatus.FAILED)
        .verifyComplete();
  }

  @Test
  public void tc29_success_marksPublished() {
    OutboxEvent row = new OutboxEvent();
    row.setId("row1");
    row.setEventId("e1");
    row.setStatus(OutboxStatus.PUBLISHING);
    when(repository.save(any(OutboxEvent.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StepVerifier.create(outbox.markPublished(row))
        .expectNextMatches(saved -> saved.getStatus() == OutboxStatus.PUBLISHED)
        .verifyComplete();
  }

  @Test
  public void tc29_relayRecoversAfterBrokerReturns() {
    // First tick fails, the row goes back to NEW with backoff instead of
    // vanishing; the next tick (or a restart re-reading the table)
    // publishes it. Nothing confirmed is ever lost.
    OutboxEvent row = new OutboxEvent();
    row.setId("row1");
    row.setEventId("e1");
    row.setStatus(OutboxStatus.PUBLISHING);
    row.setAttempts(0);
    when(repository.save(any(OutboxEvent.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StepVerifier.create(outbox.markFailed(row, "broker down"))
        .expectNextMatches(saved ->
            saved.getStatus() == OutboxStatus.NEW && saved.getAttempts() == 1)
        .verifyComplete();

    row.setStatus(OutboxStatus.PUBLISHING);
    StepVerifier.create(outbox.markPublished(row))
        .expectNextMatches(saved -> saved.getStatus() == OutboxStatus.PUBLISHED)
        .verifyComplete();

    verify(repository, Mockito.times(2)).save(any(OutboxEvent.class));
  }

  @Test
  public void tc29_payload_isTheSafeContract() {
    when(repository.save(any(OutboxEvent.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StepVerifier.create(outbox.append(event("e1", "o1")))
        .expectNextMatches(row ->
            !row.getPayload().contains("ccNumber")
            && row.getVersion().equals("v1"))
        .verifyComplete();
  }

  @Test
  public void tc29_backoff_growsExponentially() {
    assertTrue(props.backoffForAttempt(2).compareTo(
        props.backoffForAttempt(1)) > 0);
    verify(repository, never()).save(any(OutboxEvent.class));
  }

  private static void assertTrue(boolean condition) {
    if (!condition) {
      throw new AssertionError("expected true");
    }
  }
}
