package tacos;

import java.util.Date;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One auditable step of an order (TC-25).
 *
 * <p>Carries who, when, through which channel and why. It deliberately holds
 * no payment data, no user object and no password: an audit trail must explain
 * the order without becoming a second copy of sensitive data.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderStatusChange {

  private OrderStatus from;
  private OrderStatus to;
  /** User id or role label (for example {@code "kitchen:station-3"}). */
  private String changedBy;
  private Date changedAt = new Date();
  /** Where the change was requested: API, KITCHEN, SYSTEM... */
  private String origin;
  /** Short human reason, already trimmed by the workflow service. */
  private String reason;
}
