package tacos.web.api;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.api.dto.PageResponse;
import tacos.search.TacoSearchProperties;
import tacos.search.TacoSearchQuery;
import tacos.search.TacoSearchService;

/**
 * Browsable catalog (TC-19): {@code GET /api/tacos} with optional filters and
 * a validated page window.
 *
 * <p>An empty query is not an error — it is the catalog's first page, newest
 * first. Every parameter is independently optional and they intersect, so
 * {@code ?diet=VEGAN&excludeAllergen=GLUTEN} means "vegan and without gluten",
 * never "vegan or without gluten".
 */
@RestController
@RequestMapping(path = "/api/tacos", produces = "application/json")
@CrossOrigin(origins = "http://localhost:8080")
public class TacoSearchController {

  private final TacoSearchService search;
  private final TacoSearchProperties properties;

  public TacoSearchController(TacoSearchService search, TacoSearchProperties properties) {
    this.search = search;
    this.properties = properties;
  }

  @GetMapping
  public Mono<PageResponse<Taco>> tacos(
      @RequestParam(name = "name", required = false) String name,
      @RequestParam(name = "ingredientId", required = false) String ingredientId,
      @RequestParam(name = "diet", required = false) String diet,
      @RequestParam(name = "excludeAllergen", required = false) String excludeAllergen,
      @RequestParam(name = "spice", required = false) String spice,
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "size", required = false) Integer size,
      @RequestParam(name = "sort", required = false) String sort) {
    TacoSearchQuery query = TacoSearchQuery.of(name, ingredientId, diet, excludeAllergen,
        spice, page, size, sort, properties);
    return search.search(query);
  }

}
