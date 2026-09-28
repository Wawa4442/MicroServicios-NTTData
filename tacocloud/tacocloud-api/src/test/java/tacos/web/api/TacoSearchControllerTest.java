package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.SpiceLevel;
import tacos.Taco;
import tacos.api.dto.PageResponse;
import tacos.search.InvalidTacoSearchException;
import tacos.search.TacoSearchProperties;
import tacos.search.TacoSearchQuery;
import tacos.search.TacoSearchService;

/**
 * TC-19 at the HTTP boundary: what a client sees.
 *
 * <p>Two things are checked here and nowhere else. First, the response shape is
 * a page envelope and not a bare array, because a client that has to guess
 * whether it received a list or a page is a client that will guess wrong on the
 * last page. Second, a bad request is a 400 with a machine-readable code, the
 * same as every other validation failure in this API.
 */
public class TacoSearchControllerTest {

  private TacoSearchService search;
  private WebTestClient client;

  @BeforeEach
  public void setup() {
    search = mock(TacoSearchService.class);
    TacoSearchProperties properties = new TacoSearchProperties();
    when(search.search(any(TacoSearchQuery.class)))
        .thenReturn(Mono.empty());
    client = WebTestClient.bindToController(new TacoSearchController(search, properties))
        .controllerAdvice(new RestProblemHandler())
        .build();
  }

  @Test
  public void tc19_noFilters_returnsTheFirstPage() {
    when(search.search(any(TacoSearchQuery.class))).thenReturn(page());

    client.get().uri("/api/tacos")
        .exchange()
        .expectStatus().isOk()
        .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
        .expectBody()
        .jsonPath("$.content[0].name").isEqualTo("Al Pastor")
        .jsonPath("$.page").isEqualTo(0)
        .jsonPath("$.size").isEqualTo(20)
        .jsonPath("$.totalElements").isEqualTo(1)
        .jsonPath("$.totalPages").isEqualTo(1)
        .jsonPath("$.hasNext").isEqualTo(false);
  }

  @Test
  public void tc19_filters_areTranslatedAndPassedToTheService() {
    when(search.search(any(TacoSearchQuery.class))).thenReturn(page());

    client.get().uri("/api/tacos?name=Pastor&ingredientId=PORK&diet=VEGAN"
        + "&excludeAllergen=DAIRY&spice=MEDIUM&page=2&size=5&sort=name,asc")
        .exchange()
        .expectStatus().isOk();

    ArgumentCaptor<TacoSearchQuery> captor = ArgumentCaptor.forClass(TacoSearchQuery.class);
    verify(search).search(captor.capture());
    TacoSearchQuery query = captor.getValue();
    assertEquals("Pastor", query.getName());
    assertEquals("PORK", query.getIngredientId());
    assertEquals(DietaryTag.VEGAN, query.getDiet());
    assertEquals(Allergen.DAIRY, query.getExcludeAllergen());
    assertEquals(SpiceLevel.MEDIUM, query.getSpice());
    assertEquals(2, query.getPage().getPage());
    assertEquals(5, query.getPage().getSize());
    assertEquals("name", query.getSort().getField());
  }

  @Test
  public void tc19_invalidFilter_isA400WithAProblemBody() {
    client.get().uri("/api/tacos?diet=carnivore")
        .exchange()
        .expectStatus().isBadRequest()
        .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        .expectBody()
        .jsonPath("$.code").isEqualTo("invalid_search")
        .jsonPath("$.status").isEqualTo(400);
  }

  @Test
  public void tc19_unexpectedSize_isA400() {
    client.get().uri("/api/tacos?size=1000")
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.code").isEqualTo("invalid_page");
  }

  @Test
  public void tc19_invalidSort_isA400() {
    client.get().uri("/api/tacos?sort=price,asc")
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.code").isEqualTo("invalid_search");
  }

  @Test
  public void tc19_emptyCatalog_isAnEmptyPageNotAnError() {
    when(search.search(any(TacoSearchQuery.class))).thenReturn(Mono.just(
        PageResponse.of(List.of(), 0, 20, 0)));

    client.get().uri("/api/tacos")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.content").isArray()
        .jsonPath("$.totalElements").isEqualTo(0)
        .jsonPath("$.hasNext").isEqualTo(false);
  }

  @Test
  public void tc19_searchFailureIsNeverLeakedAsA200() {
    when(search.search(any(TacoSearchQuery.class)))
        .thenReturn(Mono.error(new IllegalStateException("mongo down")));

    client.get().uri("/api/tacos")
        .exchange()
        .expectStatus().is5xxServerError()
        .expectBody()
        .jsonPath("$.detail").isEqualTo("An unexpected error occurred.");
  }

  @Test
  public void tc19_unknownFilterParameterIsIgnored() {
    when(search.search(any(TacoSearchQuery.class))).thenReturn(page());

    client.get().uri("/api/tacos?colour=red")
        .exchange()
        .expectStatus().isOk();
  }

  @Test
  public void tc19_adapterExceptionReachesTheProblemHandler() {
    // The service never throws InvalidTacoSearchException; the query factory does,
    // and that happens while the controller is assembling the request. This case
    // pins that the advice is actually registered for this endpoint.
    when(search.search(any(TacoSearchQuery.class)))
        .thenReturn(Mono.error(new InvalidTacoSearchException("boom")));

    client.get().uri("/api/tacos")
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.code").isEqualTo("invalid_search");
  }

  private static Mono<PageResponse<Taco>> page() {
    Taco taco = new Taco();
    taco.setId("taco-1");
    taco.setName("Al Pastor");
    return Mono.just(PageResponse.of(List.of(taco), 0, 20, 1));
  }

}
