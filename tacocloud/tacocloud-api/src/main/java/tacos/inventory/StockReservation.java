package tacos.inventory;

import java.util.Date;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Idempotency and compensation record for one stock reservation (TC-16).
 * Created with {@code id = reservationKey} so a retry of the same logical
 * demand finds it before touching any stock. State transitions are guarded by
 * {@link #status} so a reservation is released at most once.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document
public class StockReservation {

  @Id
  private String id;

  /** Idempotency key of the logical demand (same as {@code id}). */
  private String reservationKey;

  /** Set when the order is persisted and the reservation confirmed. */
  private String orderId;

  private ReservationStatus status;

  private List<ReservedItem> items;

  private Date createdAt;

  @Version
  private Long version;

  public static StockReservation created(String reservationKey, List<ReservedItem> items) {
    StockReservation reservation = new StockReservation();
    reservation.setId(reservationKey);
    reservation.setReservationKey(reservationKey);
    reservation.setStatus(ReservationStatus.RESERVED);
    reservation.setItems(items);
    reservation.setCreatedAt(new Date());
    return reservation;
  }

}