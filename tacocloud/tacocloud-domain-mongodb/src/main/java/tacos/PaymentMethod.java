package tacos;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

@Document
@Data
@NoArgsConstructor(force=true, access=AccessLevel.PRIVATE)
@RequiredArgsConstructor
public class PaymentMethod {

  @Id
  private String id;
  
  private final User user;

  /**
   * Opaque payment token issued by the {@code PaymentGateway}. It replaces any
   * card number, so nothing here can be used to charge the customer again and
   * no PAN ever reaches the database.
   */
  private final String paymentToken;

  /** Card brand (VISA, MASTERCARD, AMEX, ...) for display only. */
  private final String brand;

  /** Last four digits of the card, for display only. */
  private final String last4;

  /** Card expiration ("10/25"), kept for display only. */
  private final String expiration;
  
}