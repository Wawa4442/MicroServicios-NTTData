package tacos.payment;

import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * Result of {@link PaymentGateway#tokenize}. Safe to persist and to send
 * anywhere: it contains no card number and no CVV.
 */
@Data
@RequiredArgsConstructor
public class TokenizedCard {

  private final String paymentToken;
  private final String brand;
  private final String last4;
  private final String expiration;

}