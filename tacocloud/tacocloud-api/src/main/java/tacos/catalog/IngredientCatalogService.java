package tacos.catalog;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.api.dto.CatalogPatchRequest;
import tacos.api.dto.StockAdjustmentRequest;
import tacos.data.IngredientRepository;
import tacos.web.api.UnknownIngredientException;

/**
 * Catalog use cases for operators: change price/availability and adjust stock.
 *
 * <p>Invariants are checked here, not in the controller:
 * <ul>
 *   <li>price is never negative;</li>
 *   <li>stock is never negative;</li>
 *   <li>{@code available = true} requires positive stock (you cannot sell air);</li>
 *   <li>{@code available = false} with stock on hand is a valid commercial
 *       pause.</li>
 * </ul>
 *
 * <p>Updates load the entity (with its {@code @Version}) and save it back, so
 * a concurrent writer surfaces as an optimistic-locking failure → HTTP 409.
 */
@Service
public class IngredientCatalogService {

  private final IngredientRepository repo;

  public IngredientCatalogService(IngredientRepository repo) {
    this.repo = repo;
  }

  public Mono<Ingredient> adjustCatalog(String id, CatalogPatchRequest request) {
    if (request.isEmpty()) {
      return Mono.error(new IngredientCatalogValidationException(
          "At least one of unitPrice or available must be present."));
    }
    return repo.findById(id)
        .switchIfEmpty(Mono.error(new UnknownIngredientException(id)))
        .flatMap(existing -> {
          if (request.getUnitPrice() != null) {
            existing.setUnitPrice(request.getUnitPrice());
          }
          if (request.getAvailable() != null) {
            existing.setAvailable(request.getAvailable());
          }
          validate(existing);
          return repo.save(existing);
        });
  }

  public Mono<Ingredient> adjustStock(String id, StockAdjustmentRequest request) {
    return repo.findById(id)
        .switchIfEmpty(Mono.error(new UnknownIngredientException(id)))
        .flatMap(existing -> {
          long next = (long) existing.getStockOnHand() + request.getDelta();
          if (next < 0) {
            return Mono.error(new StockAdjustmentRejectedException(
                id, existing.getStockOnHand(), request.getDelta()));
          }
          existing.setStockOnHand((int) next);
          validate(existing);
          return repo.save(existing);
        });
  }

  /**
   * Enforces the catalog invariants. Package-visible so tests can pin the exact
   * rules without going through the repository.
   */
  void validate(Ingredient ingredient) {
    BigDecimal price = ingredient.getUnitPrice();
    if (price == null || price.signum() < 0) {
      throw new IngredientCatalogValidationException("unitPrice must not be negative.");
    }
    if (ingredient.getStockOnHand() < 0) {
      throw new IngredientCatalogValidationException("stockOnHand must not be negative.");
    }
    if (ingredient.isAvailable() && ingredient.getStockOnHand() == 0) {
      throw new IngredientCatalogValidationException(
          "available = true requires positive stock; pause the ingredient otherwise.");
    }
  }

}