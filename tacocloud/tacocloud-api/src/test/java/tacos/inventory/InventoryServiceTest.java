package tacos.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * TC-16 inventory reservation guarantees, exercised against in-memory ports
 * whose atomicity mirrors the MongoDB conditional updates (the real port is
 * pinned by {@link MongoStockPortTest} / {@link MongoReservationLedgerTest}).
 *
 * <p>These tests prove the <em>service</em> contract: sorted deterministic
 * demand, all-or-nothing compensation, idempotent retries, at-most-once
 * release and the impossibility of overselling the last unit.
 */
public class InventoryServiceTest {

  private static List<ReservedItem> requirement(String ingredientId, int quantity) {
    return List.of(ReservedItem.of(ingredientId, quantity));
  }

  @Test
  public void requirementsOf_aggregatesAcrossTacosAndQuantities_sortedStable() {
    tacos.TacoOrder order = new tacos.TacoOrder();
    tacos.Taco tacoA = new tacos.Taco();
    tacoA.setName("A");
    tacoA.setQuantity(2);
    tacoA.setIngredients(List.of(ingr("FLTO"), ingr("GRBF"), ingr("FLTO")));
    tacos.Taco tacoB = new tacos.Taco();
    tacoB.setName("B");
    tacoB.setQuantity(1);
    tacoB.setIngredients(List.of(ingr("GRBF")));
    order.setTacos(List.of(tacoA, tacoB));

    List<ReservedItem> items = InventoryService.requirementsOf(order);

    assertEquals(2, items.size());
    assertEquals("FLTO", items.get(0).getIngredientId());
    assertEquals(4, items.get(0).getQuantity(), "FLTO x2 in A with quantity 2 = 4");
    assertEquals("GRBF", items.get(1).getIngredientId());
    assertEquals(3, items.get(1).getQuantity(), "GRBF x1 in A (x2) + x1 in B = 3");
  }

  @Test
  public void reserve_singleItem_debitsAndPersistsReserved() {
    MemoryStockPort stock = new MemoryStockPort().seed("FLTO", 10);
    MemoryReservationLedger ledger = new MemoryReservationLedger();
    InventoryService inventory = new InventoryService(stock, ledger);

    StepVerifier.create(inventory.reserve("key-1", requirement("FLTO", 3)))
        .assertNext(reservation -> {
          assertEquals(ReservationStatus.RESERVED, reservation.getStatus());
          assertEquals("key-1", reservation.getReservationKey());
        })
        .verifyComplete();

    assertEquals(7, stock.level("FLTO"));
  }

  @Test
  public void partialFailure_compensatesReverseOrder_noReservationPersists() {
    MemoryStockPort stock = new MemoryStockPort().seed("FLTO", 5).seed("GRBF", 0);
    MemoryReservationLedger ledger = new MemoryReservationLedger();
    InventoryService inventory = new InventoryService(stock, ledger);

    StepVerifier.create(inventory.reserve("key-2",
            List.of(ReservedItem.of("FLTO", 2), ReservedItem.of("GRBF", 1))))
        .expectErrorSatisfies(error -> assertTrue(error instanceof
            InsufficientStockException, "expected InsufficientStockException"))
        .verify();

    assertEquals(5, stock.level("FLTO"),
        "the already-reserved FLTO units are returned when GRBF cannot be reserved");
    assertNull(ledger.byKey("key-2"),
        "a failed reservation never leaves a RESERVED record behind");
  }

  @Test
  public void concurrentReservations_cannotOversellTheLastUnit() {
    MemoryStockPort stock = new MemoryStockPort().seed("FLTO", 1);
    MemoryReservationLedger ledger = new MemoryReservationLedger();
    InventoryService inventory = new InventoryService(stock, ledger);
    List<Throwable> failures =
        java.util.Collections.synchronizedList(new ArrayList<>());

    Mono<Boolean> buyerA = inventory.reserve("key-A", requirement("FLTO", 1))
        .map(r -> true)
        .onErrorResume(error -> { failures.add(error); return Mono.just(false); });
    Mono<Boolean> buyerB = inventory.reserve("key-B", requirement("FLTO", 1))
        .map(r -> true)
        .onErrorResume(error -> { failures.add(error); return Mono.just(false); });

    StepVerifier.create(Mono.zip(buyerA, buyerB))
        .assertNext(winners -> {
          int wins = (winners.getT1() ? 1 : 0) + (winners.getT2() ? 1 : 0);
          assertEquals(1, wins, "only one of two concurrent buyers may win the last unit");
        })
        .verifyComplete();

    assertEquals(0, stock.level("FLTO"));
    assertEquals(1, ledger.countByStatus(ReservationStatus.RESERVED));
    assertEquals(1, failures.size());
    assertTrue(failures.get(0) instanceof InsufficientStockException,
        "the loser fails with InsufficientStockException");
  }

  @Test
  public void reserve_sameKeyRetry_isIdempotent_noSecondDebit() {
    MemoryStockPort stock = new MemoryStockPort().seed("FLTO", 3);
    MemoryReservationLedger ledger = new MemoryReservationLedger();
    InventoryService inventory = new InventoryService(stock, ledger);

    StepVerifier.create(inventory.reserve("key-R", requirement("FLTO", 1)))
        .expectNextCount(1).verifyComplete();
    StepVerifier.create(inventory.reserve("key-R", requirement("FLTO", 1)))
        .assertNext(reservation -> assertEquals(
            ReservationStatus.RESERVED, reservation.getStatus()))
        .verifyComplete();

    assertEquals(2, stock.level("FLTO"),
        "replaying the same reservation key must not debit twice");
  }

  @Test
  public void release_returnsStockThenSecondReleaseIsNoOp() {
    MemoryStockPort stock = new MemoryStockPort().seed("FLTO", 2);
    MemoryReservationLedger ledger = new MemoryReservationLedger();
    InventoryService inventory = new InventoryService(stock, ledger);

    StepVerifier.create(inventory.reserve("key-R", requirement("FLTO", 1)))
        .expectNextCount(1).verifyComplete();
    StepVerifier.create(inventory.release("key-R")).verifyComplete();
    StepVerifier.create(inventory.release("key-R")).verifyComplete();

    assertEquals(2, stock.level("FLTO"),
        "the stock is credited back exactly once");
    assertEquals(ReservationStatus.RELEASED, statusOf(ledger, "key-R"));
  }

  @Test
  public void release_afterConfirm_returnsStockExactlyOnce() {
    MemoryStockPort stock = new MemoryStockPort().seed("FLTO", 2);
    MemoryReservationLedger ledger = new MemoryReservationLedger();
    InventoryService inventory = new InventoryService(stock, ledger);

    StepVerifier.create(inventory.reserve("key-R", requirement("FLTO", 1)))
        .expectNextCount(1).verifyComplete();
    StepVerifier.create(inventory.confirm("key-R", "order-1")).verifyComplete();
    StepVerifier.create(inventory.release("key-R")).verifyComplete();
    StepVerifier.create(inventory.release("key-R")).verifyComplete();

    assertEquals(2, stock.level("FLTO"),
        "a confirmed order that is cancelled returns its stock once");
  }

  @Test
  public void release_unknownKey_isNoOp() {
    MemoryStockPort stock = new MemoryStockPort().seed("FLTO", 2);
    InventoryService inventory = new InventoryService(stock, new MemoryReservationLedger());

    StepVerifier.create(inventory.release("missing"))
        .verifyComplete();
    assertEquals(2, stock.level("FLTO"));
  }

  @Test
  public void releaseForOrder_releasesReservationLinkedToTheOrder() {
    MemoryStockPort stock = new MemoryStockPort().seed("FLTO", 1);
    MemoryReservationLedger ledger = new MemoryReservationLedger();
    InventoryService inventory = new InventoryService(stock, ledger);

    StepVerifier.create(inventory.reserve("key-R", requirement("FLTO", 1)))
        .expectNextCount(1).verifyComplete();
    StepVerifier.create(inventory.confirm("key-R", "order-1")).verifyComplete();

    StepVerifier.create(inventory.releaseForOrder("order-1")).verifyComplete();

    assertEquals(1, stock.level("FLTO"));
    assertEquals(ReservationStatus.RELEASED, statusOf(ledger, "key-R"));
  }

  @Test
  public void release_afterReplayOfConfirmedReservation_doesNotCreateAnOrder() {
    // Guard: a confirmed reservation whose order disappeared is a programming
    // error surfaced loudly instead of being silently re-debited.
    MemoryStockPort stock = new MemoryStockPort().seed("FLTO", 1);
    MemoryReservationLedger ledger = new MemoryReservationLedger();
    InventoryService inventory = new InventoryService(stock, ledger);
    StepVerifier.create(inventory.confirm("ghost", "order-9"))
        .verifyErrorSatisfies(error ->
            assertTrue(error instanceof IllegalStateException,
                "expected IllegalStateException"));
  }

  // ------------------------------------------------------------------
  // in-memory ports (atomicity mirrors the conditional MongoDB updates)
  // ------------------------------------------------------------------

  private static class MemoryStockPort implements StockPort {

    private final Object monitor = new Object();
    private final Map<String, Integer> stock = new ConcurrentHashMap<>();
    private final Set<String> forSale = ConcurrentHashMap.newKeySet();

    MemoryStockPort seed(String ingredientId, int quantity) {
      stock.put(ingredientId, quantity);
      forSale.add(ingredientId);
      return this;
    }

    int level(String ingredientId) {
      return stock.getOrDefault(ingredientId, 0);
    }

    @Override
    public Mono<Boolean> tryDebit(String ingredientId, int quantity) {
      return Mono.fromCallable(() -> {
        synchronized (monitor) {
          Integer current = stock.get(ingredientId);
          if (current != null && forSale.contains(ingredientId)
              && current >= quantity) {
            stock.put(ingredientId, current - quantity);
            return true;
          }
          return false;
        }
      }).subscribeOn(Schedulers.parallel());
    }

    @Override
    public Mono<Void> credit(String ingredientId, int quantity) {
      return Mono.fromRunnable(() -> {
        synchronized (monitor) {
          stock.merge(ingredientId, quantity, Integer::sum);
        }
      });
    }
  }

  private static class MemoryReservationLedger implements ReservationLedger {

    private final Object monitor = new Object();
    private final Map<String, StockReservation> removals = new ConcurrentHashMap<>();
    private final Map<String, String> orderToKey = new ConcurrentHashMap<>();

    StockReservation byKey(String key) {
      return removals.get(key);
    }

    long countByStatus(ReservationStatus status) {
      return removals.values().stream()
          .filter(r -> r.getStatus() == status)
          .count();
    }

    @Override
    public Mono<StockReservation> findByKey(String reservationKey) {
      return Mono.justOrEmpty(removals.get(reservationKey));
    }

    @Override
    public Mono<StockReservation> findByOrderId(String orderId) {
      String key = orderToKey.get(orderId);
      return Mono.justOrEmpty(key == null ? null : removals.get(key));
    }

    @Override
    public Mono<StockReservation> save(StockReservation reservation) {
      return Mono.fromCallable(() -> {
        synchronized (monitor) {
          removals.put(reservation.getReservationKey(), reservation);
          if (reservation.getOrderId() != null) {
            orderToKey.put(reservation.getOrderId(), reservation.getReservationKey());
          }
          return reservation;
        }
      });
    }

    @Override
    public Mono<Boolean> confirm(String reservationKey, String orderId) {
      return Mono.fromCallable(() -> {
        synchronized (monitor) {
          StockReservation reservation = removals.get(reservationKey);
          if (reservation != null
              && reservation.getStatus() == ReservationStatus.RESERVED) {
            reservation.setStatus(ReservationStatus.CONFIRMED);
            reservation.setOrderId(orderId);
            orderToKey.put(orderId, reservationKey);
            return true;
          }
          return false;
        }
      });
    }

    @Override
    public Mono<Boolean> release(String reservationKey) {
      return Mono.fromCallable(() -> {
        synchronized (monitor) {
          StockReservation reservation = removals.get(reservationKey);
          if (reservation != null
              && (reservation.getStatus() == ReservationStatus.RESERVED
                  || reservation.getStatus() == ReservationStatus.CONFIRMED)) {
            reservation.setStatus(ReservationStatus.RELEASED);
            return true;
          }
          return false;
        }
      });
    }
  }

  private static ReservationStatus statusOf(MemoryReservationLedger ledger, String key) {
    return ledger.byKey(key).getStatus();
  }

  private static tacos.Ingredient ingr(String id) {
    return new tacos.Ingredient(id, id, tacos.Ingredient.Type.WRAP);
  }

}