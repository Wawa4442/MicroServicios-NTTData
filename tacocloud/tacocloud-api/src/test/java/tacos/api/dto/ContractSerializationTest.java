package tacos.api.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.PaymentMethod;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;

/**
 * TC-08 contract tests: the API no longer exposes persistence entities, users
 * or card data. These JSON/serialization tests document the stable wire shape.
 */
public class ContractSerializationTest {

  private static final ObjectMapper JACKSON = new ObjectMapper()
      .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

  private static final User ALICE = alice();
  private static final Ingredient FLTO = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);
  private static final Ingredient GRBF = new Ingredient("GRBF", "Ground Beef", Type.PROTEIN);
  private static final OrderMapper ORDER_MAPPER = new OrderMapper();
  private static final IngredientMapper INGREDIENT_MAPPER = new IngredientMapper();

  @Test
  public void orderResponse_neverLeaksPersistentOrSensitiveFields() throws Exception {
    TacoOrder order = fullOrder();

    String json = JACKSON.writeValueAsString(OrderResponse.from(order));

    assertFalse(json.contains("password"), "password must never appear in the response");
    assertFalse(json.contains("ccNumber"), "PAN must never appear in the response");
    assertFalse(json.contains("ccCVV"), "CVV must never appear in the response");
    assertFalse(json.contains("authorities"), "authorities must never appear in the response");
    assertFalse(json.contains("\"user\""), "embedded users must never appear in the response");
  }

  @Test
  public void ingredientResponse_isPlainBusinessData() throws Exception {
    String json = JACKSON.writeValueAsString(INGREDIENT_MAPPER.toResponse(FLTO));

    assertTrue(json.contains("\"id\":\"FLTO\""));
    assertTrue(json.contains("\"name\":\"Flour Tortilla\""));
    assertTrue(json.contains("\"type\":\"WRAP\""));
    assertFalse(json.contains("ccNumber"));
    assertFalse(json.contains("password"));
  }

  @Test
  public void orderCreateRequest_isImmuneToServerOwnedFields() throws Exception {
    OrderCreateRequest request = JACKSON.readValue(
        "{\"deliveryName\":\"Craig Walls\",\"deliveryState\":\"TX\",\"deliveryZip\":\"76227\","
            + "\"id\":\"order9\",\"placedAt\":1,\"ccNumber\":\"4111111111111111\","
            + "\"ccCVV\":\"123\",\"status\":\"PLACED\",\"userId\":\"other\","
            + "\"tacos\":[{\"name\":\"Carnivore\",\"ingredientIds\":[\"FLTO\"]}]}",
        OrderCreateRequest.class);

    String json = JACKSON.writeValueAsString(request);
    assertFalse(json.contains("\"id\""), "request DTO exposes no id");
    assertFalse(json.contains("placedAt"), "request DTO exposes no placedAt");
    assertFalse(json.contains("ccNumber"), "request DTO exposes no PAN");
    assertFalse(json.contains("ccCVV"), "request DTO exposes no CVV");
    assertFalse(json.contains("status"), "request DTO exposes no status");
    assertFalse(json.contains("userId"), "request DTO exposes no user identity");
    assertEquals("Craig Walls", request.getDeliveryName());
  }

  @Test
  public void mapper_requestToEntity_transformOnlyAvailableData() {
    OrderCreateRequest request = request();

    TacoOrder order = ORDER_MAPPER.toEntity(request, resolvedTacos(), ALICE, card());

    assertEquals("Craig Walls", order.getDeliveryName());
    assertEquals("76227", order.getDeliveryZip());
    assertEquals(ALICE, order.getUser());
    assertEquals(1, order.getTacos().size());
    assertEquals("Carnivore", order.getTacos().get(0).getName());
    assertEquals(Arrays.asList(FLTO, GRBF), order.getTacos().get(0).getIngredients());
    assertNull(order.getId(), "the entity id is still null until the server assigns it");
    assertFalse(order.getPlacedAt().getTime() == 1L, "placedAt is server-owned");
    assertEquals("pay-1", order.getPaymentMethodId(),
        "the entity references the payment by opaque id, never by card data");
  }

  @Test
  public void mapper_entityToResponse_carriesStableBusinessValues() {
    TacoOrder order = fullOrder();

    OrderResponse response = OrderResponse.from(order);

    assertEquals("order1", response.getId());
    assertEquals("Craig Walls", response.getDeliveryName());
    assertEquals(1, response.getTacos().size());
    assertEquals("Carnivore", response.getTacos().get(0).getName());
    assertEquals("FLTO", response.getTacos().get(0).getIngredients().get(0).getId());
  }

  @Test
  public void mapper_merge_preservesIdentityAndOwner() {
    OrderCreateRequest request = request();
    TacoOrder existing = fullOrder();
    existing.setId("order1");

    TacoOrder merged = ORDER_MAPPER.merge(existing, request, resolvedTacos(), null);

    assertEquals("order1", merged.getId());
    assertEquals(existing.getPlacedAt(), merged.getPlacedAt());
    assertEquals(ALICE, merged.getUser());
    assertEquals("Craig Walls", merged.getDeliveryName());
    assertEquals("pay-1", merged.getPaymentMethodId(),
        "the existing payment reference is preserved when the request carries none");
  }

  private static OrderCreateRequest request() {
    OrderCreateRequest request = new OrderCreateRequest();
    request.setDeliveryName("Craig Walls");
    request.setDeliveryStreet("123 North Street");
    request.setDeliveryCity("Cross Roads");
    request.setDeliveryState("TX");
    request.setDeliveryZip("76227");
    TacoLineRequest line = new TacoLineRequest();
    line.setName("Carnivore");
    line.setIngredientIds(Arrays.asList("FLTO", "GRBF"));
    request.setTacos(Collections.singletonList(line));
    request.setPaymentMethodId("pay-1");
    return request;
  }

  private static List<Taco> resolvedTacos() {
    Taco taco = new Taco();
    taco.setName("Carnivore");
    taco.setIngredients(Arrays.asList(FLTO, GRBF));
    return Collections.singletonList(taco);
  }

  private static TacoOrder fullOrder() {
    TacoOrder order = new TacoOrder();
    order.setId("order1");
    order.setUser(ALICE);
    order.setPlacedAt(new Date(1L));
    order.setDeliveryName("Craig Walls");
    order.setDeliveryStreet("123 North Street");
    order.setDeliveryCity("Cross Roads");
    order.setDeliveryState("TX");
    order.setDeliveryZip("76227");
    order.setPaymentMethodId("pay-1");
    order.setTacos(resolvedTacos());
    return order;
  }

  private static PaymentMethod card() {
    PaymentMethod method = new PaymentMethod(ALICE, "tok_abc123", "VISA", "1111", "10/25");
    method.setId("pay-1");
    return method;
  }

  private static User alice() {
    User user = new User("alice", "secret", "Alice", "1 Oak", "Austin", "TX",
        "78701", "555", "alice@taco.cl");
    user.setId("userA");
    return user;
  }

}