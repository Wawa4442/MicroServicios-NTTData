package tacos.web.api;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Taco;
import tacos.api.dto.TacoDesignValidationRequest;
import tacos.api.dto.TacoDesignValidationResponse;
import tacos.classification.TacoClassification;
import tacos.classification.TacoClassificationResponse;
import tacos.classification.TacoClassificationService;
import tacos.data.IngredientRepository;
import tacos.data.TacoRepository;
import tacos.rules.TacoValidator;

/**
 * Public taco catalog and design validation (TC-17/TC-18). Validation runs the
 * same injected rule collection used by order creation, so what the endpoint
 * approves, the order flow will accept.
 */
@RestController
@RequestMapping(path = "/api/tacos", produces = "application/json")
@CrossOrigin(origins="http://localhost:8080")
public class TacoController {
  private TacoRepository tacoRepo;
  private IngredientRepository ingredientRepo;
  private TacoValidator validator;
  private TacoClassificationService classification;

  public TacoController(TacoRepository tacoRepo, IngredientRepository ingredientRepo,
      TacoValidator validator, TacoClassificationService classification) {
    this.tacoRepo = tacoRepo;
    this.ingredientRepo = ingredientRepo;
    this.validator = validator;
    this.classification = classification;
  }

  @GetMapping(params="recent")
  public Flux<Taco> recentTacos() {
    return tacoRepo.findAll().take(12);
  }

  @PostMapping(consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<Taco> postTaco(@RequestBody Taco taco) {
    validator.validateOrThrow(taco.getIngredients());
    return tacoRepo.save(taco);
  }

  @GetMapping("/{id}")
  public Mono<Taco> tacoById(@PathVariable("id") String id) {
    return tacoRepo.findById(id);
  }

  /**
   * Taco Physics: verifies a proposed design without saving, pricing, quoting
   * or reserving anything. Returns every violation at once; the client can
   * render all of them.
   */
  @PostMapping(path = "/validate", consumes = "application/json")
  public Mono<TacoDesignValidationResponse> validate(
      @RequestBody TacoDesignValidationRequest request) {
    List<String> ids = request.getIngredientIds() == null
        ? new ArrayList<>() : request.getIngredientIds();
    return resolve(ids).map(ingredients -> TacoDesignValidationResponse.of(
        validator.violations(ingredients), classification.classify(ingredients)));
  }

  /**
   * TC-17: derived dietary, allergen and spice classification of a stored taco.
   */
  @GetMapping("/{id}/classification")
  public Mono<ResponseEntity<TacoClassificationResponse>> classification(
      @PathVariable("id") String id) {
    return tacoRepo.findById(id)
        .map(taco -> ResponseEntity.ok(TacoClassificationResponse.from(
            classification.classify(taco.getIngredients()))))
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  private Mono<List<Ingredient>> resolve(List<String> ids) {
    return Flux.fromIterable(ids)
        .concatMap(id -> ingredientRepo.<Ingredient>findById(id)
            .switchIfEmpty(Mono.error(new UnknownIngredientException(id))))
        .collectList();
  }

}