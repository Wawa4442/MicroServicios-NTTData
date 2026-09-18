package tacos;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;

@Data
@Document
public class TacoOrder implements Serializable {
  private static final long serialVersionUID = 1L;

  @Id
  private String id;
  private Date placedAt = new Date();

  private User user;

  private String deliveryName;

  private String deliveryStreet;

  private String deliveryCity;

  private String deliveryState;

  private String deliveryZip;

  /**
   * Opaque reference to the {@link PaymentMethod} used to pay for this order.
   * Card data never leaves the payment gateway; the order only carries this id.
   */
  private String paymentMethodId;


  private List<Taco> tacos = new ArrayList<>();

  // ------------------------------------------------------------------
  // Server-owned money (TC-14/TC-15). The client never sends these; they are
  // recomputed from the catalog and the coupon engine on every create/replace.
  // ------------------------------------------------------------------

  private String currency = "USD";

  private BigDecimal subtotal;

  private BigDecimal discount;

  private String couponCode;

  private BigDecimal total;

  public void addTaco(Taco design) {
    this.tacos.add(design);
}

}
