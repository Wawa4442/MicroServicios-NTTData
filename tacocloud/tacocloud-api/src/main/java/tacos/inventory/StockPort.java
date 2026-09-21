package tacos.inventory;

import reactor.core.publisher.Mono;

/**
 * Hexagonal port for the per-ingredient stock mutations (TC-16).
 *
 * <p>The contract is atomic: {@code tryDebit} only succeeds when
 * {@code stockOnHand >= quantity} AND the ingredient is available, and it
 * decrements in the same operation, so two concurrent buyers of the last unit
 * never both succeed and the stock never goes negative. The implementation
 * (Mongo conditional update, in-memory monitor in tests) decides how the
 * atomicity is achieved; the service only orchestrates, it never reads then
 * writes.
 */
public interface StockPort {

  Mono<Boolean> tryDebit(String ingredientId, int quantity);

  Mono<Void> credit(String ingredientId, int quantity);

}