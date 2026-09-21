package tacos.inventory;

/**
 * Lifecycle of a stock reservation.
 *
 * <ul>
 *   <li>{@code RESERVED}: stock has been conditionally decremented and the
 *       reservation is pending order confirmation;</li>
 *   <li>{@code CONFIRMED}: the order was persisted, the reservation is final;</li>
 *   <li>{@code RELEASED}: the stock was returned exactly once (compensation or
 *       cancellation).</li>
 * </ul>
 */
public enum ReservationStatus {
  RESERVED,
  CONFIRMED,
  RELEASED
}