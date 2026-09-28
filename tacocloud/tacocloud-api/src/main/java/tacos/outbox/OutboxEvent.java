package tacos.outbox;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;

/**
 * One event waiting for (or done with) broker delivery (TC-29).
 *
 * <p>The payload is the JSON of the versioned {@code OrderEvent} contract
 * (TC-27): safe snapshots only, never card data. The row and the order are
 * written in the same local Mongo transaction, so a crash before the commit
 * leaves zero order and zero outbox, and a confirmed order always has an
 * outbox row for the relay to deliver.
 */
@Data
@Document(collection = "outboxEvents")
public class OutboxEvent {

  @Id
  private String id;

  /** Idempotency key end to end; also the consumer dedup key (TC-30). */
  @Indexed(unique = true)
  private String eventId;

  private String aggregateId;

  private String eventType;

  private String version;

  /** Serialized OrderEvent JSON. */
  private String payload;

  private String correlationId;

  private OutboxStatus status = OutboxStatus.NEW;

  private int attempts;

  private Instant createdAt = Instant.now();

  private Instant updatedAt = Instant.now();

  private Instant nextAttemptAt = Instant.now();

  private String lastError;

  /** Which relay instance claimed it; lets two publishers split, not clash. */
  private String claimedBy;
}
