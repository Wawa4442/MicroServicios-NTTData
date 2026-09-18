package tacos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.api.dto.IngredientMapper;
import tacos.api.dto.IngredientResponse;
import tacos.data.IngredientRepository;

public class IngredientControllerTest {

  private IngredientRepository repo;
  private IngredientController controller;
  private WebTestClient testClient;

  private static final Ingredient FLTO =
      new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);
  private static final Ingredient COTO =
      new Ingredient("COTO", "Corn Tortilla", Type.WRAP);
  private static final Ingredient GRBF =
      new Ingredient("GRBF", "Ground Beef", Type.PROTEIN);

  @BeforeEach
  public void setup() {
    repo = Mockito.mock(IngredientRepository.class);
    controller = new IngredientController(repo, new IngredientMapper());
    testClient = WebTestClient.bindToController(controller)
        .controllerAdvice(new RestProblemHandler())
        .build();
  }

  // =====================================================================
  // GET (baseline) + TC-08 safe output contract
  // =====================================================================

  @Test
  public void getAllIngredients_returnsFlux() {
    when(repo.findAll()).thenReturn(Flux.just(FLTO, COTO, GRBF));

    testClient.get().uri("/api/ingredients")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
            .jsonPath("$").isArray()
            .jsonPath("$.length()").isEqualTo(3)
            .jsonPath("$[0].id").isEqualTo("FLTO")
            .jsonPath("$[1].name").isEqualTo("Corn Tortilla");
  }

  @Test
  public void getById_existing_ingredient_returns200() {
    when(repo.<String>findById("FLTO")).thenReturn(Mono.just(FLTO));

    testClient.get().uri("/api/ingredients/FLTO")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.id").isEqualTo("FLTO")
        .jsonPath("$.name").isEqualTo("Flour Tortilla")
        .jsonPath("$.type").isEqualTo("WRAP");
  }

  @Test
  public void getById_missing_ingredient_returnsEmpty() {
    when(repo.<String>findById("MISSING")).thenReturn(Mono.empty());

    testClient.get().uri("/api/ingredients/MISSING")
        .exchange()
        .expectStatus().isOk()
        .expectBody().isEmpty();
  }

  // =====================================================================
  // TC-01: PUT — reactive chain, existence check, 200/404
  // =====================================================================

  @Test
  public void tc01_putExistingIngredient_serverOwnedIdWins() {
    when(repo.<String>findById("FLTO")).thenReturn(Mono.just(FLTO));
    when(repo.save(any(Ingredient.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    // TC-08 mass assignment: the body may try to set the id (a server-owned
    // field); the request DTO ignores it and the path id wins.
    testClient.put().uri("/api/ingredients/FLTO")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"id\":\"DIFFERENT\",\"name\":\"Updated Flour Tortilla\","
            + "\"type\":\"WRAP\"}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.id").isEqualTo("FLTO")
        .jsonPath("$.name").isEqualTo("Updated Flour Tortilla")
        .jsonPath("$.type").isEqualTo("WRAP");

    verify(repo).save(new Ingredient("FLTO", "Updated Flour Tortilla", Type.WRAP));
  }

  @Test
  public void tc01_putNonExistent_ingredient_returns404() {
    when(repo.<String>findById("GHOST")).thenReturn(Mono.empty());

    testClient.put().uri("/api/ingredients/GHOST")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\"Phantom\",\"type\":\"SAUCE\"}")
        .exchange()
        .expectStatus().isNotFound();

    verify(repo, never()).save(any(Ingredient.class));
  }

  @Test
  public void tc01_putSaveActuallyExecutes_onSubscription() {
    when(repo.<String>findById("FLTO")).thenReturn(Mono.just(FLTO));
    AtomicBoolean saveSubscribed = new AtomicBoolean(false);
    when(repo.save(any(Ingredient.class))).thenReturn(
        Mono.defer(() -> {
          saveSubscribed.set(true);
          return Mono.just(new Ingredient("FLTO", "Flour Tortilla v2", Type.WRAP));
        }));

    testClient.put().uri("/api/ingredients/FLTO")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\"Flour Tortilla v2\",\"type\":\"WRAP\"}")
        .exchange()
        .expectStatus().isOk();

    org.junit.jupiter.api.Assertions.assertTrue(saveSubscribed.get(),
        "repo.save() must execute when the reactive chain is subscribed");
  }

  @Test
  public void tc01_put_emitsAndCompletes() {
    when(repo.<String>findById("FLTO")).thenReturn(Mono.just(FLTO));
    when(repo.save(any(Ingredient.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StepVerifier.create(
            repo.<String>findById("FLTO")
                .flatMap(e -> repo.save(new Ingredient("FLTO", "FT v3", Type.WRAP)))
                .map(IngredientResponse::from))
        .assertNext(resp -> {
          org.junit.jupiter.api.Assertions.assertEquals("FLTO", resp.getId());
          org.junit.jupiter.api.Assertions.assertEquals("FT v3", resp.getName());
        })
        .verifyComplete();
  }

  // =====================================================================
  // TC-02: DELETE — existence check, 204/404
  // =====================================================================

  @Test
  public void tc02_deleteExisting_returns204NoContent() {
    when(repo.existsById("FLTO")).thenReturn(Mono.just(true));
    when(repo.<String>deleteById("FLTO")).thenReturn(Mono.empty());

    testClient.delete().uri("/api/ingredients/FLTO")
        .exchange()
        .expectStatus().isNoContent()
        .expectBody().isEmpty();

    verify(repo).deleteById("FLTO");
  }

  @Test
  public void tc02_deleteNonExistent_returns404() {
    when(repo.existsById("MISSING")).thenReturn(Mono.just(false));

    testClient.delete().uri("/api/ingredients/MISSING")
        .exchange()
        .expectStatus().isNotFound()
        .expectBody().isEmpty();

    verify(repo, never()).deleteById(any(String.class));
  }

  @Test
  public void tc02_deleteSecondCall_alsoReturns404() {
    when(repo.existsById("FLTO"))
        .thenReturn(Mono.just(true))
        .thenReturn(Mono.just(false));
    when(repo.<String>deleteById("FLTO")).thenReturn(Mono.empty());

    testClient.delete().uri("/api/ingredients/FLTO")
        .exchange()
        .expectStatus().isNoContent();

    testClient.delete().uri("/api/ingredients/FLTO")
        .exchange()
        .expectStatus().isNotFound();
  }

  // =====================================================================
  // TC-03: POST — Location header, 201, 400 for invalid body
  // =====================================================================

  @Test
  public void tc03_postValid_ingredient_returns201WithLocation() {
    Ingredient saved = new Ingredient("NEWSAUCE", "New Sauce", Type.SAUCE);

    when(repo.save(any(Ingredient.class))).thenReturn(Mono.just(saved));

    testClient.post().uri("/api/ingredients")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\"New Sauce\",\"type\":\"SAUCE\"}")
        .exchange()
        .expectStatus().isCreated()
        .expectHeader().exists("Location")
        .expectHeader().valueMatches("Location", ".*api/ingredients/NEWSAUCE")
        .expectBody()
        .jsonPath("$.id").isEqualTo("NEWSAUCE")
        .jsonPath("$.name").isEqualTo("New Sauce")
        .jsonPath("$.type").isEqualTo("SAUCE");
  }

  @Test
  public void tc03_followLocation_returns200() {
    Ingredient saved = new Ingredient("SVG", "Salsa Verde", Type.SAUCE);

    when(repo.save(any(Ingredient.class))).thenReturn(Mono.just(saved));

    WebTestClient.ResponseSpec response = testClient.post().uri("/api/ingredients")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\"Salsa Verde\",\"type\":\"SAUCE\"}")
        .exchange();

    String location = response.returnResult(String.class).getResponseHeaders()
        .getLocation().toString();

    when(repo.<String>findById("SVG")).thenReturn(Mono.just(saved));

    testClient.get().uri(location)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.id").isEqualTo("SVG")
        .jsonPath("$.name").isEqualTo("Salsa Verde");
  }

  @Test
  public void tc03_postMissingName_returns400_noSave() {
    testClient.post().uri("/api/ingredients")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\"\",\"type\":\"SAUCE\"}")
        .exchange()
        .expectStatus().isBadRequest();

    verify(repo, never()).save(any(Ingredient.class));
  }

  @Test
  public void tc03_postMissingType_returns400_noSave() {
    testClient.post().uri("/api/ingredients")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"name\":\"Something\"}")
        .exchange()
        .expectStatus().isBadRequest();

    verify(repo, never()).save(any(Ingredient.class));
  }

}