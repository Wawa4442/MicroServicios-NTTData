package tacos.inventory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Taco;
import tacos.TacoOrder;

/**
 * Inventory use cases (TC-16): reserve stock before an order is persisted,
 * confirm it once the order exists, and release it on compensation or
 * cancellation.
 *
 * <p>Guarantees delivered here:
 * <ul>
 *   <li>every debit goes through {@link StockPort#tryDebit} (atomic, no
 *       read-modify-write) and ingredients are processed in stable sorted
 *       order, so two concurrent buyers cannot oversell the last unit;</li>
 *   <li>a failure mid-way compensates exactly the already-reserved items;</li>
 *   <li>the reservation key makes the demand idempotent: a retry never debits
 *       twice, and a release is a guarded transition that can only happen
 *       once;</li>
 *   <li>no {@code block()} or {@code subscribe()}: every method returns a
 *       publisher.</li>
 * </ul>
 */
@Service
public class InventoryService {

  private final StockPort stock;
  private final ReservationLedger ledger;

  public InventoryService(StockPort stock, ReservationLedger ledger) {
    this.stock = stock;
    this.ledger = ledger;
  }

  /**
   * Aggregates the stock demand of an order draft: the same ingredient used in
   * two tacos (or two units of one taco) is summed into a single item. The
   * result is sorted by ingredient id, so reservation order is deterministic.
   */
  public static List<ReservedItem> requirementsOf(TacoOrder order) {
    Map<String, Integer> byId = new LinkedHashMap<>();
    if (order.getTacos() != null) {
      for (Taco taco : order.getTacos()) {
        if (taco == null || taco.getIngredients() == null) {
          continue;
        }
        int quantity = Math.max(1, taco.getQuantity());
        for (Ingredient ingredient : taco.getIngredients()) {
          if (ingredient != null && ingredient.getId() != null) {
            byId.merge(ingredient.getId(), quantity, Integer::sum);
          }
        }
      }
    }
    List<ReservedItem> items = new ArrayList<>();
    byId.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(entry -> items.add(ReservedItem.of(entry.getKey(), entry.getValue())));
    return items;
  }

  /**
   * Reserves every required quantity or fails with
   * {@link InsufficientStockException} without leaving any debit behind.
   * Idempotent on the key: an existing RESERVED/CONFIRMED reservation is
   * returned untouched (no second debit); only a RELEASED one is re-created.
   */
  public Mono<StockReservation> reserve(String reservationKey,
      List<ReservedItem> requirements) {
    if (requirements == null || requirements.isEmpty()) {
      return Mono.just(StockReservation.created(reservationKey, List.of()));
    }
    return ledger.findByKey(reservationKey)
        .flatMap(existing -> {
          if (existing.getStatus() == ReservationStatus.CONFIRMED
              || existing.getStatus() == ReservationStatus.RESERVED) {
            return Mono.just(existing);
          }
          return doReserve(reservationKey, requirements);
        })
        .switchIfEmpty(doReserve(reservationKey, requirements));
  }

  /**
   * Marks the reservation as final once its order was persisted. The guarded
   * transition is attempted first; only when it does not happen (already
   * CONFIRMED by an idempotent retry, or missing) is the state inspected and
   * an error raised. An empty result never means "missing": the guarded
   * transition itself completes empty, so the missing-case check is scoped to
   * the inspection of a real retained state.
   */
  public Mono<Void> confirm(String reservationKey, String orderId) {
    return ledger.confirm(reservationKey, orderId)
        .flatMap(transitioned -> {
          if (transitioned) {
            return Mono.empty();
          }
          return ledger.findByKey(reservationKey)
              .flatMap(current -> {
                if (current.getStatus() == ReservationStatus.CONFIRMED) {
                  return Mono.<Void>empty();
                }
                return Mono.<Void>error(new IllegalStateException(
                    "Reservation " + reservationKey + " cannot be confirmed from "
                        + current.getStatus() + "."));
              })
              .switchIfEmpty(Mono.<Void>error(new IllegalStateException(
                  "No reservation found for key " + reservationKey + ".")));
        });
  }

  /**
   * Returns the stock of a reservation at most once: a valid reservation
   * ({@code RESERVED} or {@code CONFIRMED}) transitions to {@code RELEASED}
   * and only then are the units credited. Absent and already-RELEASED
   * reservations are no-ops. A confirmed order keeps its stock until a
   * deliberate delete/replace returns it, but it can never be returned twice.
   */
  public Mono<Void> release(String reservationKey) {
    return ledger.findByKey(reservationKey)
        .flatMap(r -> ledger.release(r.getReservationKey())
            .flatMap(transitioned -> transitioned
                ? creditItems(r.getItems())
                : Mono.empty()))
        .then();
  }

  /** Releases the reservation linked to an order, e.g. before deleting it. */
  public Mono<Void> releaseForOrder(String orderId) {
    return ledger.findByOrderId(orderId)
        .flatMap(r -> release(r.getReservationKey()))
        .then();
  }

  private Mono<StockReservation> doReserve(String reservationKey,
      List<ReservedItem> requirements) {
    List<ReservedItem> debited = new ArrayList<>();
    return Flux.fromIterable(requirements)
        .concatMap(item -> stock.tryDebit(item.getIngredientId(), item.getQuantity())
            .flatMap(ok -> {
              if (!ok) {
                return Mono.error(new InsufficientStockException(
                    item.getIngredientId(), item.getQuantity()));
              }
              debited.add(item);
              return Mono.just(item);
            }))
        .then(ledger.save(StockReservation.created(reservationKey, requirements)))
        .onErrorResume(error -> creditItems(reverse(debited)).then(Mono.error(error)));
  }

  private Mono<Void> creditItems(List<ReservedItem> items) {
    return Flux.fromIterable(items)
        .concatMap(item -> stock.credit(item.getIngredientId(), item.getQuantity()))
        .then();
  }

  private static List<ReservedItem> reverse(List<ReservedItem> items) {
    List<ReservedItem> out = new ArrayList<>(items);
    java.util.Collections.reverse(out);
    return out;
  }

}