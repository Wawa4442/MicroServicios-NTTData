package tacos.idempotency;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;

/**
 * One HTTP attempt remembered so a retry does not charge twice (TC-34).
 *
 * <p>The {@code id} is {@code userId:key}, so two customers can reuse the
 * same key without colliding. The {@code requestHash} is a SHA-256 over the
 * canonical business fields: same key plus same hash replays the stored
 * order, same key plus another hash is a 409. The order and the outbox row
 * are still committed together (TC-29); this record only remembers which
 * order that commit produced.
 */
@Data
@Document("idempotencyRecords")
public class IdempotencyRecord {

  @Id
  private String id;

  private String key;

  private String userId;

  private String requestHash;

  private String orderId;

  private IdempotencyStatus status = IdempotencyStatus.PENDING;

  private Instant createdAt;

  private Instant updatedAt;

  private Instant expiresAt;
}
