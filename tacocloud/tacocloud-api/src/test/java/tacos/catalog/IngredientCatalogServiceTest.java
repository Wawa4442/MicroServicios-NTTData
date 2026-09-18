package tacos.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.OptimisticLockingFailureException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.api.dto.CatalogPatchRequest;
import tacos.api.dto.StockAdjustmentRequest;
import tacos.data.IngredientRepository;

public class IngredientCatalogServiceTest {

  private IngredientRepository repo;
  private IngredientCatalogService service;

  @BeforeEach
  public void setup() {
    repo = Mockito.mock(IngredientRepository.class);
    service = new IngredientCatalogService(repo);
  }

  @Test
  public void tc13_catalogPrice_isBigDecimalAndExact() {
    when(repo.findById("FLTO")).thenReturn(Mono.just(ingredient()));
    when(repo.save(any(Ingredient.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    CatalogPatchRequest request = new CatalogPatchRequest();
    request.setUnitPrice(new BigDecimal("1.25"));

    StepVerifier.create(service.adjustCatalog("FLTO", request))
        .assertNext(saved -> {
          org.junit.jupiter.api.Assertions.assertEquals(0,
              new BigDecimal("1.25").compareTo(saved.getUnitPrice()));
          org.junit.jupiter.api.Assertions.assertEquals(2, saved.getUnitPrice().scale());
        })
        .verifyComplete();
  }

  @Test
  public void tc13_negativePrice_isRejected_withoutSaving() {
    when(repo.findById("FLTO")).thenReturn(Mono.just(ingredient()));

    CatalogPatchRequest request = new CatalogPatchRequest();
    request.setUnitPrice(new BigDecimal("-0.01"));

    StepVerifier.create(service.adjustCatalog("FLTO", request))
        .expectError(IngredientCatalogValidationException.class)
        .verify();

    verify(repo, never()).save(any(Ingredient.class));
  }

  @Test
  public void tc13_availableWithoutStock_isRejected() {
    Ingredient paused = ingredient();
    paused.setStockOnHand(0);
    when(repo.findById("FLTO")).thenReturn(Mono.just(paused));

    CatalogPatchRequest request = new CatalogPatchRequest();
    request.setAvailable(true);

    StepVerifier.create(service.adjustCatalog("FLTO", request))
        .expectError(IngredientCatalogValidationException.class)
        .verify();

    verify(repo, never()).save(any(Ingredient.class));
  }

  @Test
  public void tc13_negativeStockAdjustment_isRejected_withoutSaving() {
    Ingredient scarce = ingredient();
    scarce.setStockOnHand(3);
    when(repo.findById("FLTO")).thenReturn(Mono.just(scarce));

    StockAdjustmentRequest request = new StockAdjustmentRequest();
    request.setDelta(-5);

    StepVerifier.create(service.adjustStock("FLTO", request))
        .expectErrorSatisfies(error -> {
          org.junit.jupiter.api.Assertions.assertTrue(
              error instanceof StockAdjustmentRejectedException);
          org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("FLTO"));
        })
        .verify();

    verify(repo, never()).save(any(Ingredient.class));
  }

  @Test
  public void tc13_positiveStockAdjustment_isApplied() {
    when(repo.findById("FLTO")).thenReturn(Mono.just(ingredient()));
    when(repo.save(any(Ingredient.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StockAdjustmentRequest request = new StockAdjustmentRequest();
    request.setDelta(5);

    StepVerifier.create(service.adjustStock("FLTO", request))
        .assertNext(saved -> org.junit.jupiter.api.Assertions.assertEquals(
            15, saved.getStockOnHand()))
        .verifyComplete();
  }

  @Test
  public void tc13_emptyPatch_isRejected() {
    StepVerifier.create(service.adjustCatalog("FLTO", new CatalogPatchRequest()))
        .expectError(IngredientCatalogValidationException.class)
        .verify();
  }

  @Test
  public void tc13_optimisticLockingFailure_isPropagated() {
    when(repo.findById("FLTO")).thenReturn(Mono.just(ingredient()));
    when(repo.save(any(Ingredient.class)))
        .thenReturn(Mono.error(new OptimisticLockingFailureException("stale version")));

    CatalogPatchRequest request = new CatalogPatchRequest();
    request.setAvailable(true);

    StepVerifier.create(service.adjustCatalog("FLTO", request))
        .expectError(OptimisticLockingFailureException.class)
        .verify();
  }

  @Test
  public void tc13_seedLikeIngredients_passValidation() {
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
        service.validate(new Ingredient("FLTO", "Flour Tortilla", Type.WRAP,
            new BigDecimal("0.75"), true, 100, 20)));
  }

  private static Ingredient ingredient() {
    Ingredient ingredient = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP,
        new BigDecimal("1.00"), true, 10, 2);
    ingredient.setVersion(0L);
    return ingredient;
  }

}