package tacos.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import tacos.api.dto.OrderCreateRequest;
import tacos.api.dto.TacoLineRequest;

/**
 * TC-34 key format and hashing.
 */
public class IdempotencyKeysTest {

  @Test
  public void tc34_missingHeader_meansNoKey() {
    assertNull(IdempotencyKeys.normalizeHeader(null));
    assertNull(IdempotencyKeys.normalizeHeader("   "));
  }

  @Test
  public void tc34_badFormat_isRejected() {
    assertThrows(InvalidIdempotencyKeyException.class,
        () -> IdempotencyKeys.normalizeHeader("short"));
    assertThrows(InvalidIdempotencyKeyException.class,
        () -> IdempotencyKeys.normalizeHeader("has space in key 123456"));
    assertThrows(InvalidIdempotencyKeyException.class,
        () -> IdempotencyKeys.normalizeHeader("evil\ninjection-123456"));
  }

  @Test
  public void tc34_goodKey_isKept() {
    assertEquals("order-1234_ABCD",
        IdempotencyKeys.normalizeHeader("  order-1234_ABCD  "));
  }

  @Test
  public void tc34_keyIsScopedByUser() {
    assertNotEquals(
        IdempotencyKeys.recordId("alice", "key-12345678"),
        IdempotencyKeys.recordId("bob", "key-12345678"));
    assertTrue(IdempotencyKeys.recordId("alice", "key-12345678").startsWith("alice:"));
  }

  @Test
  public void tc34_samePurchase_hashesSame_differentPurchase_hashesDifferent() {
    OrderCreateRequest first = purchase("WELCOME10");
    OrderCreateRequest second = purchase("welcome10");
    assertEquals(IdempotencyKeys.canonicalHash(first),
        IdempotencyKeys.canonicalHash(second));

    OrderCreateRequest other = purchase("FLAT5");
    assertNotEquals(IdempotencyKeys.canonicalHash(first),
        IdempotencyKeys.canonicalHash(other));
  }

  private static OrderCreateRequest purchase(String coupon) {
    OrderCreateRequest request = new OrderCreateRequest();
    request.setDeliveryName("Alice");
    request.setDeliveryStreet("Main 1");
    request.setDeliveryCity("Austin");
    request.setDeliveryState("TX");
    request.setDeliveryZip("78701");
    TacoLineRequest line = new TacoLineRequest();
    line.setName("Classic");
    line.setIngredientIds(List.of("FLTO", "CHED"));
    line.setQuantity(2);
    request.setTacos(List.of(line));
    request.setCouponCode(coupon);
    request.setPaymentMethodId("pm-1");
    return request;
  }
}
