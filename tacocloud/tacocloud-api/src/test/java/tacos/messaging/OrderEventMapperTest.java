package tacos.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;

import tacos.OrderStatus;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;

/**
 * TC-27 on the API side: the mapper only emits kitchen-safe snapshots.
 */
public class OrderEventMapperTest {

  private final OrderEventMapper mapper = new OrderEventMapper();

  @Test
  public void tc27_createdCarriesSnapshot_notReferences() {
    TacoOrder order = order();
    OrderEvent event = mapper.toCreated(order, "corr-9");

    assertEquals(OrderEventType.ORDER_CREATED, event.getEventType());
    assertEquals("v1", event.getVersion());
    assertEquals("corr-9", event.getCorrelationId());
    assertEquals("o1", event.getPayload().getOrderId());
    assertEquals("CREATED", event.getPayload().getStatus());
    assertEquals(1, event.getPayload().getTacoCount());
    assertEquals(List.of("Test Taco"), event.getPayload().getTacoNames());
    assertEquals("Austin", event.getPayload().getDeliveryCity());
  }

  @Test
  public void tc27_statusChangedKeepsPrevious() {
    TacoOrder order = order();
    order.setStatus(OrderStatus.ACCEPTED);
    OrderEvent event = mapper.toStatusChanged(order, OrderStatus.CREATED, null);

    assertEquals(OrderEventType.STATUS_CHANGED, event.getEventType());
    assertEquals("ACCEPTED", event.getPayload().getStatus());
    assertEquals("CREATED", event.getPayload().getPreviousStatus());
  }

  @Test
  public void tc27_payloadNeverLeaksPaymentOrUser() throws Exception {
    com.fasterxml.jackson.databind.ObjectMapper json =
        new com.fasterxml.jackson.databind.ObjectMapper();
    json.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    String payload = json.writeValueAsString(mapper.toCreated(order(), null));

    assertFalse(payload.contains("pm-secret"));
    assertFalse(payload.contains("1 Secret St"));
    assertFalse(payload.contains("78701"));
    assertTrue(payload.contains("o1"));
  }

  private static TacoOrder order() {
    TacoOrder order = new TacoOrder();
    order.setId("o1");
    order.setPlacedAt(new Date());
    order.setStatus(OrderStatus.CREATED);
    order.setDeliveryName("U");
    order.setDeliveryStreet("1 Secret St");
    order.setDeliveryCity("Austin");
    order.setDeliveryState("TX");
    order.setDeliveryZip("78701");
    order.setPaymentMethodId("pm-secret");
    User user = new User("u", "pw", "U", "s", "c", "TX", "78701", "p", "u@t.co");
    user.setId("userA");
    order.setUser(user);
    Taco taco = new Taco();
    taco.setName("Test Taco");
    taco.setIngredients(List.of());
    order.setTacos(List.of(taco));
    order.setCurrency("USD");
    order.setTotal(new BigDecimal("9.99"));
    return order;
  }
}
