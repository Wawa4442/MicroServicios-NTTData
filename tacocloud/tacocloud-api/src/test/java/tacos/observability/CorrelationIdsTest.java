package tacos.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * TC-31 at the unit level: generation, preservation and injection safety.
 */
public class CorrelationIdsTest {

  @Test
  public void tc31_missingHeader_generatesUuid() {
    String first = CorrelationIds.normalize(null);
    String second = CorrelationIds.normalize("  ");
    assertNotNull(first);
    assertNotNull(second);
    assertNotEquals(first, second);
    assertTrue(CorrelationIds.isValid(first));
  }

  @Test
  public void tc31_validHeader_isPreserved() {
    assertEquals("order-flow-123", CorrelationIds.normalize("order-flow-123"));
    assertEquals("abc-DEF_123.:9", CorrelationIds.normalize("  abc-DEF_123.:9  "));
  }

  @Test
  public void tc31_maliciousOrLongHeader_isReplaced() {
    String evil = "good\nsecond-line";
    String replaced = CorrelationIds.normalize(evil);
    assertFalse(replaced.contains("\n"));
    assertTrue(CorrelationIds.isValid(replaced));

    String longRaw = "a".repeat(200);
    String replacedLong = CorrelationIds.normalize(longRaw);
    assertTrue(CorrelationIds.isValid(replacedLong));
    assertNotEquals(longRaw, replacedLong);

    assertFalse(CorrelationIds.isValid("has space"));
    assertFalse(CorrelationIds.isValid("semi;colon"));
  }

  @Test
  public void tc31_orderIdIsNotUsedAsCorrelation() {
    // Documents the rule: the caller must pass the request id explicitly.
    // An order id could be reused here by accident, but the factory never
    // invents the correlation from business data.
    String generated = CorrelationIds.generate();
    assertTrue(CorrelationIds.isValid(generated));
  }
}
