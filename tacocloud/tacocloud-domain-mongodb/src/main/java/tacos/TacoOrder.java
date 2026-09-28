package tacos;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;

/**
 * A placed order. The {@code user} is embedded because an order is a private
 * document of one customer: the history (TC-23) always reads through
 * {@code user._id}, and the compound index below matches that access path
 * exactly, so no query has to filter a global page down in memory.
 */
@Data
@Document
@CompoundIndex(name = "order_user_placed_idx",
    def = "{'user._id': 1, 'placedAt': -1, '_id': -1}")
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

  /**
   * Idempotency key of the inventory reservation (TC-16). Lets a retry of the
   * same demand recognize its own reservation and a deletion release it.
   */
  private String reservationKey;

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
