package tacos.consumer;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;

/**
 * Idempotency record of the kitchen consumer (TC-30).
 *
 * <p>The key is {@code eventId}, never {@code orderId}: the same order
 * produces many events over its life, and each one must be processed exactly
 * once in effect. The unique index is what turns "delivered twice" into
 * "processed once".
 */
@Data
@Document(collection = "processedEvents")
public class ProcessedEvent {

  @Id
  private String id;

  @Indexed(unique = true)
  private String eventId;

  private String orderId;

  private String eventType;

  private String result;

  private Instant processedAt = Instant.now();
}
