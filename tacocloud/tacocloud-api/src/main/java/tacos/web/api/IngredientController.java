package tacos.web.api;

import java.net.URI;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import javax.validation.Valid;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.api.dto.IngredientMapper;
import tacos.api.dto.IngredientRequest;
import tacos.api.dto.IngredientResponse;
import tacos.data.IngredientRepository;

@RestController
@RequestMapping(path="/api/ingredients", produces="application/json")
@CrossOrigin(origins="http://localhost:8080")
public class IngredientController {

  private IngredientRepository repo;
  private IngredientMapper mapper;

  @Autowired
  public IngredientController(IngredientRepository repo, IngredientMapper mapper) {
    this.repo = repo;
    this.mapper = mapper;
  }

  @GetMapping
  public Flux<IngredientResponse> allIngredients() {
    return repo.findAll().map(IngredientResponse::from);
  }

  @GetMapping("/{id}")
  public Mono<IngredientResponse> byId(@PathVariable String id) {
    return repo.findById(id).map(IngredientResponse::from);
  }

  @PutMapping("/{id}")
  public Mono<ResponseEntity<IngredientResponse>> updateIngredient(
          @PathVariable String id, @RequestBody @Valid IngredientRequest request) {
    return repo.findById(id)
        .flatMap(existing -> repo.save(mapper.toEntity(id, request)))
        .map(saved -> ResponseEntity.ok(IngredientResponse.from(saved)))
        .defaultIfEmpty(ResponseEntity.notFound().build());
  }

  @PostMapping
  public Mono<ResponseEntity<IngredientResponse>> postIngredient(
          @RequestBody @Valid IngredientRequest request, ServerWebExchange exchange) {
    return repo.save(mapper.toEntity(null, request))
        .map(saved -> {
          URI location = URI.create(exchange.getRequest().getURI().getPath()
              + "/" + saved.getId());
          return ResponseEntity.created(location).body(IngredientResponse.from(saved));
        });
  }

  @DeleteMapping("/{id}")
  public Mono<ResponseEntity<Void>> deleteIngredient(@PathVariable String id) {
    return repo.existsById(id)
        .flatMap(exists -> exists
            ? repo.deleteById(id).then(Mono.just(ResponseEntity.noContent().<Void>build()))
            : Mono.just(ResponseEntity.notFound().build()));
  }

}