package tacos.outbox;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.messaging.OrderEvent;

/**
 * Local side of reliable delivery (TC-29).
 *
 * <p>{@code append} stores the event as {@code NEW} in the same transaction
 * as the order (see {@code OrderPlacementService}); {@code claimBatch} moves
 * due rows to {@code PUBLISHING} with a single conditional write per row, so
 * two relay instances never own the same event. Success marks
 * {@code PUBLISHED}; a broker failure schedules the next attempt with
 * backoff, and an exhausted event stays {@code FAILED} where an operator can
 * see it instead of vanishing.
 */
@Service
public class OutboxService {

  private final OutboxRepository repository;
  private final ReactiveMongoTemplate mongo;
  private final OutboxProperties props;
  private final ObjectMapper mapper;
  private final String instanceId = UUID.randomUUID().toString();

  public OutboxService(OutboxRepository repository, ReactiveMongoTemplate mongo,
                       OutboxProperties props, ObjectMapper mapper) {
    this.repository = repository;
    this.mongo = mongo;
    this.props = props;
    this.mapper = mapper;
  }

  public Mono<OutboxEvent> append(OrderEvent event) {
    OutboxEvent row = new OutboxEvent();
    row.setEventId(event.getEventId());
    row.setAggregateId(event.getPayload() == null ? null
        : event.getPayload().getOrderId());
    row.setEventType(event.getEventType() == null ? null
        : event.getEventType().name());
    row.setVersion(event.getVersion());
    row.setCorrelationId(event.getCorrelationId());
    row.setStatus(OutboxStatus.NEW);
    row.setCreatedAt(Instant.now());
    row.setUpdatedAt(Instant.now());
    row.setNextAttemptAt(Instant.now());
    try {
      row.setPayload(mapper.writeValueAsString(event));
    } catch (Exception e) {
      return Mono.error(new IllegalStateException("Cannot serialize outbox event", e));
    }
    return repository.save(row);
  }

  public OrderEvent readPayload(OutboxEvent row) {
    try {
      return mapper.readValue(row.getPayload(), OrderEvent.class);
    } catch (Exception e) {
      throw new IllegalStateException(
          "Outbox payload for event " + row.getEventId() + " is not readable", e);
    }
  }

  /**
   * Claims up to {@code limit} due events for this instance. Each claim is
   * one conditional write; a lost race returns empty (the winner owns the
   * row), so two relays never process the same event.
   */
  public Flux<OutboxEvent> claimBatch(int limit) {
    int size = Math.max(1, Math.min(limit, props.getBatchSize()));
    return repository.findByStatus(OutboxStatus.NEW)
        .take(size)
        .concatMap(this::tryClaim);
  }

  private Mono<OutboxEvent> tryClaim(OutboxEvent candidate) {
    Instant now = Instant.now();
    Query query = new Query(new Criteria().andOperator(
        Criteria.where("_id").is(candidate.getId()),
        Criteria.where("status").is(OutboxStatus.NEW),
        Criteria.where("nextAttemptAt").lte(now)));
    Update update = new Update()
        .set("status", OutboxStatus.PUBLISHING)
        .set("claimedBy", instanceId)
        .set("updatedAt", now);
    FindAndModifyOptions options = FindAndModifyOptions.options().returnNew(true);
    // Mongo returns the row only when the conditions matched, so a returned
    // row is owned by this instance; a lost race returns empty and is
    // skipped, never processed twice.
    return mongo.findAndModify(query, update, options, OutboxEvent.class)
        .filter(row -> row.getStatus() == OutboxStatus.PUBLISHING)
        .onErrorResume(e -> Mono.empty());
  }

  public Mono<OutboxEvent> markPublished(OutboxEvent row) {
    row.setStatus(OutboxStatus.PUBLISHED);
    row.setUpdatedAt(Instant.now());
    row.setLastError(null);
    return repository.save(row);
  }

  public Mono<OutboxEvent> markFailed(OutboxEvent row, String error) {
    int attempts = row.getAttempts() + 1;
    row.setAttempts(attempts);
    row.setUpdatedAt(Instant.now());
    row.setLastError(safeError(error));
    if (attempts >= props.getMaxAttempts()) {
      row.setStatus(OutboxStatus.FAILED);
      return repository.save(row);
    }
    row.setStatus(OutboxStatus.NEW);
    row.setNextAttemptAt(Instant.now().plus(props.backoffForAttempt(attempts)));
    row.setClaimedBy(null);
    return repository.save(row);
  }

  private static String safeError(String error) {
    if (error == null) {
      return "publish failed";
    }
    String trimmed = error.trim();
    return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
  }
}
