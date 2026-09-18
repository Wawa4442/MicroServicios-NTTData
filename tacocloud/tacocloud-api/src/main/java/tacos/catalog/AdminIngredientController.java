package tacos.catalog;

import javax.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.api.dto.CatalogPatchRequest;
import tacos.api.dto.IngredientAdminResponse;
import tacos.api.dto.StockAdjustmentRequest;
import tacos.data.IngredientRepository;

/**
 * Operator endpoints for the commercial and inventory attributes of the
 * catalog. Access is restricted to ADMIN by the security configuration
 * ({@code /api/admin/**}).
 */
@RestController
@RequestMapping(path = "/api/admin/ingredients", produces = "application/json")
public class AdminIngredientController {

  private final IngredientRepository repo;
  private final IngredientCatalogService catalogService;

  public AdminIngredientController(IngredientRepository repo,
                                   IngredientCatalogService catalogService) {
    this.repo = repo;
    this.catalogService = catalogService;
  }

  @GetMapping("/{id}")
  public Mono<ResponseEntity<IngredientAdminResponse>> byId(@PathVariable String id) {
    return repo.findById(id)
        .map(IngredientAdminResponse::from)
        .map(ResponseEntity::ok)
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  @PatchMapping(path = "/{id}/catalog", consumes = "application/json")
  public Mono<IngredientAdminResponse> patchCatalog(@PathVariable String id,
      @RequestBody @Valid CatalogPatchRequest request) {
    return catalogService.adjustCatalog(id, request).map(IngredientAdminResponse::from);
  }

  @PostMapping(path = "/{id}/stock-adjustments", consumes = "application/json")
  public Mono<IngredientAdminResponse> adjustStock(@PathVariable String id,
      @RequestBody @Valid StockAdjustmentRequest request) {
    return catalogService.adjustStock(id, request).map(IngredientAdminResponse::from);
  }

}