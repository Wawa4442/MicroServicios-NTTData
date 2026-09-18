package tacos.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import reactor.test.StepVerifier;

/**
 * TC-12: the payment gateway is the only component that ever sees raw card
 * data. It must return an opaque token plus display-only attributes, and keep
 * no state, so no PAN or CVV can reach the database or the API.
 */
public class PaymentGatewayTest {

  @Test
  public void tokenize_returnsOpaqueToken_andDisplayOnlyAttributes() {
    StepVerifier.create(new FakePaymentGateway().tokenize("4111111111111111", "321", "10/25"))
        .assertNext(card -> {
          assertNotNull(card.getPaymentToken());
          assertTrue(card.getPaymentToken().startsWith("tok_"),
              "the token is opaque and gateway-issued");
          assertFalse(card.getPaymentToken().contains("41111111"),
              "the raw card number must not be embedded in the token");
          assertFalse(card.getPaymentToken().contains("321"),
              "the CVV must not be embedded in the token");
          assertEquals("VISA", card.getBrand());
          assertEquals("1111", card.getLast4(),
              "only the last four digits are kept, and only for display");
          assertEquals("10/25", card.getExpiration());
        })
        .verifyComplete();
  }

  @Test
  public void tokenize_guessesBrandFromPrefix() {
    StepVerifier.create(new FakePaymentGateway().tokenize("5454545454545454", "123", "12/28"))
        .assertNext(card -> assertEquals("MASTERCARD", card.getBrand()))
        .verifyComplete();

    StepVerifier.create(new FakePaymentGateway().tokenize("371449635398431", "222", "12/28"))
        .assertNext(card -> assertEquals("AMEX", card.getBrand()))
        .verifyComplete();
  }

}