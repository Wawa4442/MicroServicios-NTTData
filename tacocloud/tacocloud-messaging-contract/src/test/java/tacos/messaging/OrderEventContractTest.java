package tacos.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * TC-27: the versioned JSON contract, in the contract module itself so every
 * transport inherits the same guarantees.
 */
public class OrderEventContractTest {

  private ObjectMapper mapper() {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    return mapper;
  }

  private OrderEvent sample() {
    OrderEventPayload payload = new OrderEventPayload();
    payload.setOrderId("o1");
    payload.setStatus("CREATED");
    payload.setTacoCount(2);
    payload.setTacoNames(List.of("Carnitas", "Veggie"));
    payload.setCurrency("USD");
    payload.setTotal(new BigDecimal("18.50"));
    payload.setPlacedAt(Instant.parse("2026-09-01T12:00:00Z"));
    payload.setDeliveryCity("Austin");
    payload.setDeliveryState("TX");
    return OrderEvent.of(OrderEventType.ORDER_CREATED, payload, "corr-1");
  }

  @Test
  public void tc27_v1_roundTrip() throws Exception {
    ObjectMapper mapper = mapper();
    OrderEvent event = sample();

    String json = mapper.writeValueAsString(event);
    OrderEvent read = mapper.readValue(json, OrderEvent.class);

    assertEquals(event.getEventId(), read.getEventId());
    assertEquals(OrderEventType.ORDER_CREATED, read.getEventType());
    assertEquals("v1", read.getVersion());
    assertEquals("o1", read.getPayload().getOrderId());
    assertEquals("corr-1", read.getCorrelationId());
    assertNotNull(read.getOccurredAt());
  }

  @Test
  public void tc27_json_hasNoSensitiveFields() throws Exception {
    String json = mapper().writeValueAsString(sample());

    assertTrue(!json.contains("ccNumber") && !json.contains("ccCVV"),
        "card data must never ride the event");
    assertTrue(!json.contains("password"), "passwords must never ride the event");
    assertTrue(!json.contains("\"user\""), "the user object must never ride the event");
    assertTrue(!json.contains("deliveryStreet"), "street is not kitchen business");
  }

  @Test
  public void tc27_envelope_alwaysCarriesIdsAndVersion() throws Exception {
    JsonNode node = mapper().readTree(mapper().writeValueAsString(sample()));

    assertTrue(node.hasNonNull("eventId"));
    assertTrue(node.hasNonNull("eventType"));
    assertTrue(node.hasNonNull("version"));
    assertTrue(node.hasNonNull("occurredAt"));
    // UUID shape, so dedup keys are stable across brokers.
    UUID.fromString(node.get("eventId").asText());
  }

  @Test
  public void tc27_v1Consumer_ignoresCompatibleNewField() throws Exception {
    ObjectMapper mapper = mapper();
    JsonNode node = mapper.readTree(mapper.writeValueAsString(sample()));
    ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("loyaltyPoints", 12);

    OrderEvent read = mapper.treeToValue(node, OrderEvent.class);
    assertEquals(OrderEventType.ORDER_CREATED, read.getEventType());
  }

  @Test
  public void tc27_snapshot_detectsIncompatibleChange() throws Exception {
    // The snapshot lists the payload fields a v1 consumer relies on. Adding
    // a *required* field or renaming one must update this test on purpose:
    // that is the point, compatible evolution stays optional.
    JsonNode payload = mapper().readTree(mapper().writeValueAsString(sample()))
        .get("payload");
    List<String> fields = new java.util.ArrayList<>();
    payload.fieldNames().forEachRemaining(fields::add);
    for (String expected : List.of("orderId", "status", "tacoCount", "tacoNames",
        "currency", "total", "placedAt", "deliveryCity", "deliveryState")) {
      assertTrue(fields.contains(expected), "payload lost v1 field: " + expected);
    }
  }
}
