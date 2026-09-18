package tacos.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;
import tacos.data.IngredientRepository;
import tacos.web.api.RestProblemHandler;

public class AdminIngredientControllerTest {

  private WebTestClient client(IngredientCatalogService service) {
    return WebTestClient.bindToController(
            new AdminIngredientController(Mockito.mock(IngredientRepository.class), service))
        .controllerAdvice(new RestProblemHandler())
        .build();
  }

  @Test
  public void tc13_negativeStockAdjustment_returns422Problem() {
    IngredientCatalogService service = Mockito.mock(IngredientCatalogService.class);
    when(service.adjustStock(anyString(), any()))
        .thenReturn(Mono.error(new StockAdjustmentRejectedException("FLTO", 3, -5)));

    client(service).post().uri("/api/admin/ingredients/FLTO/stock-adjustments")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"delta\":-5}")
        .exchange()
        .expectStatus().isEqualTo(422)
        .expectBody()
        .jsonPath("$.code").isEqualTo("insufficient_stock")
        .jsonPath("$.status").isEqualTo(422);
  }

  @Test
  public void tc13_catalogInvariantViolation_returns422Problem() {
    IngredientCatalogService service = Mockito.mock(IngredientCatalogService.class);
    when(service.adjustCatalog(anyString(), any()))
        .thenReturn(Mono.error(new IngredientCatalogValidationException(
            "available = true requires positive stock")));

    client(service).patch().uri("/api/admin/ingredients/FLTO/catalog")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"available\":true}")
        .exchange()
        .expectStatus().isEqualTo(422)
        .expectBody()
        .jsonPath("$.code").isEqualTo("ingredient_catalog_invalid");
  }

  @Test
  public void tc13_optimisticLockingConflict_returns409Problem() {
    IngredientCatalogService service = Mockito.mock(IngredientCatalogService.class);
    when(service.adjustCatalog(anyString(), any()))
        .thenReturn(Mono.error(new OptimisticLockingFailureException("stale")));

    client(service).patch().uri("/api/admin/ingredients/FLTO/catalog")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"unitPrice\":\"2.00\"}")
        .exchange()
        .expectStatus().isEqualTo(409)
        .expectBody()
        .jsonPath("$.code").isEqualTo("conflict")
        .jsonPath("$.status").isEqualTo(409);
  }

  @Test
  public void tc13_adminView_exposesOperationalMetadata() {
    IngredientCatalogService service = Mockito.mock(IngredientCatalogService.class);
    when(service.adjustCatalog(anyString(), any())).thenReturn(Mono.just(
        new tacos.Ingredient("FLTO", "Flour Tortilla", tacos.Ingredient.Type.WRAP,
            new java.math.BigDecimal("0.75"), true, 42, 10)));

    client(service).patch().uri("/api/admin/ingredients/FLTO/catalog")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"unitPrice\":\"0.75\"}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.stockOnHand").isEqualTo(42)
        .jsonPath("$.reorderLevel").isEqualTo(10)
        .jsonPath("$.unitPrice").isEqualTo(0.75);
  }

}