package tacos.web.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.api.dto.FavoriteResponse;
import tacos.api.dto.PageResponse;
import tacos.favorites.FavoriteProperties;
import tacos.favorites.FavoriteService;
import tacos.paging.PageBounds;

/**
 * Favorites of the authenticated customer (TC-21).
 *
 * <p>Every route lives under {@code /api/users/me}, so there is no path segment
 * in which a customer identifier could be supplied or tampered with. Adding
 * one would be a new vulnerability, not a new feature.
 *
 * <p>PUT is used for "mark as favorite" because it names the resource being
 * created: the resulting state is fully determined by the URL, so repeating
 * the call must not create a second favorite. POST would promise exactly the
 * opposite, and would need a de-duplication scheme bolted on afterwards.
 */
@RestController
@RequestMapping(path = "/api/users/me/favorites", produces = "application/json")
@CrossOrigin(origins = "http://localhost:8080")
public class FavoriteController {

  private final FavoriteService favorites;
  private final CallerIdentityResolver identities;
  private final FavoriteProperties properties;

  public FavoriteController(FavoriteService favorites, CallerIdentityResolver identities,
      FavoriteProperties properties) {
    this.favorites = favorites;
    this.identities = identities;
    this.properties = properties;
  }

  @PutMapping(path = "/{tacoId}")
  public Mono<FavoriteResponse> add(@PathVariable("tacoId") String tacoId) {
    return identities.requiredUserId()
        .flatMap(userId -> favorites.add(userId, tacoId));
  }

  @DeleteMapping(path = "/{tacoId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public Mono<Void> remove(@PathVariable("tacoId") String tacoId) {
    return identities.requiredUserId()
        .flatMap(userId -> favorites.remove(userId, tacoId));
  }

  @GetMapping
  public Mono<PageResponse<FavoriteResponse>> list(
      @RequestParam(name = "page", required = false) Integer page,
      @RequestParam(name = "size", required = false) Integer size) {
    PageBounds bounds = PageBounds.of(page, size,
        properties.getDefaultSize(), properties.getMaxSize());
    return identities.requiredUserId()
        .flatMap(userId -> favorites.list(userId, bounds.getPage(), bounds.getSize()));
  }

}
