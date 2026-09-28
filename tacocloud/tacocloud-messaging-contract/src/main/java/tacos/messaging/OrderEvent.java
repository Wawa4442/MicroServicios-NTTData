package tacos.messaging;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Versioned envelope every transport carries unchanged (TC-27).
 *
 * <p>{@code eventId} is the idempotency key end to end (TC-30 deduplicates on
 * it, never on {@code orderId}); {@code correlationId} ties the HTTP request
 * to the event and its logs; {@code version} is {@code "v1"} today and only
 * grows by adding optional fields. A v1 consumer ignores unknown fields
 * instead of failing, so compatible evolution never breaks the kitchen.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderEvent {

  public static final String CURRENT_VERSION = "v1";

  private String eventId;
  private OrderEventType eventType;
  private String version = CURRENT_VERSION;
  private Instant occurredAt;
  private String correlationId;
  private OrderEventPayload payload;

  public static OrderEvent of(OrderEventType type, OrderEventPayload payload,
                              String correlationId) {
    return new OrderEvent(UUID.randomUUID().toString(), type,
        CURRENT_VERSION, Instant.now(), correlationId, payload);
  }
}
