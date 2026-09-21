package tacos.inventory;

import reactor.core.publisher.Mono;

/**
 * Persistence port for {@link StockReservation} (TC-16).
 *
 * <p>{@code confirm} and {@code release} are guarded state transitions: they
 * only succeed when the reservation is still {@code RESERVED}, so a release
 * can never happen twice nor refund an already-confirmed order. Implementations
 * rely on a conditional update, not on read-check-write.
 */
public interface ReservationLedger {

  Mono<StockReservation> findByKey(String reservationKey);

  Mono<StockReservation> findByOrderId(String orderId);

  Mono<StockReservation> save(StockReservation reservation);

  /** RESERVED -> CONFIRMED, recording the order id. False means it already was. */
  Mono<Boolean> confirm(String reservationKey, String orderId);

  /** RESERVED -> RELEASED. False means no-op (absent, already released or confirmed). */
  Mono<Boolean> release(String reservationKey);

}