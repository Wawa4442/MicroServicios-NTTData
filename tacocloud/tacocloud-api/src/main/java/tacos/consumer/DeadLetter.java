package tacos.consumer;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;

/**
 * Poison message parking lot (TC-30).
 *
 * <p>When an event exhausts its retries or is permanently invalid, it lands
 * here with the cause and the correlation id, and without any sensitive
 * payload: card data never had a seat on the event in the first place. An
 * operator replays it explicitly after fixing the cause; nothing here
 * redelivers itself, so there is no infinite loop.
 */
@Data
@Document(collection = "deadLetters")
public class DeadLetter {

  @Id
  private String id;

  @Indexed(unique = true)
  private String eventId;

  private String destination;

  private String eventType;

  private String eventVersion;

  private String orderId;

  private String correlationId;

  private String cause;

  private String error;

  private int attempts;

  private Instant failedAt = Instant.now();
}
