package tacos.payment;

import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * Fake {@link PaymentGateway} used outside production. It simulates a token
 * provider: the PAN and CVV are consumed to build an opaque token plus a few
 * display-only attributes, and are then discarded. The gateway keeps no state,
 * so nothing here can leak the card number.
 */
@Component
public class FakePaymentGateway implements PaymentGateway {

  @Override
  public Mono<TokenizedCard> tokenize(String ccNumber, String ccCVV, String expiration) {
    String token = "tok_" + Long.toHexString(System.currentTimeMillis())
        + Integer.toHexString(ccNumber.hashCode());
    return Mono.just(new TokenizedCard(token, brandOf(ccNumber), lastFour(ccNumber), expiration));
  }

  private static String brandOf(String ccNumber) {
    if (ccNumber != null && ccNumber.startsWith("4")) {
      return "VISA";
    }
    if (ccNumber != null && (ccNumber.startsWith("5")
        || ccNumber.startsWith("2"))) {
      return "MASTERCARD";
    }
    if (ccNumber != null && ccNumber.startsWith("3")) {
      return "AMEX";
    }
    return "UNKNOWN";
  }

  private static String lastFour(String ccNumber) {
    if (ccNumber == null || ccNumber.length() < 4) {
      return ccNumber;
    }
    return ccNumber.substring(ccNumber.length() - 4);
  }

}