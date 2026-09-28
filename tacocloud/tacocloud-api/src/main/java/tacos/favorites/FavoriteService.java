package tacos.favorites;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import tacos.Favorite;
import tacos.Taco;
import tacos.api.dto.FavoriteResponse;
import tacos.api.dto.PageResponse;
import tacos.data.FavoriteRepository;
import tacos.data.TacoRepository;
import tacos.web.api.TacoNotFoundException;

/**
 * Favorites use cases (TC-21).
 *
 * <p>Identity is a parameter, not something read from the request body: the
 * controller resolves it from the security context and passes it in, so no code
 * path in this service can be talked into acting on somebody else's list.
 *
 * <p>Idempotency is enforced twice, on purpose. The happy path checks whether
 * the row already exists, and the unique {@code (userId, tacoId)} index is what
 * makes two simultaneous PUTs collapse into one row. The second writer sees a
 * {@code DuplicateKeyException} and is answered with the row the first writer
 * produced — a success, not a conflict, because asking twice for the same
 * favorite is not a conflict of state.
 */
@Service
public class FavoriteService {

  private final FavoriteRepository favorites;
  private final TacoRepository tacos;

  public FavoriteService(FavoriteRepository favorites, TacoRepository tacos) {
    this.favorites = favorites;
    this.tacos = tacos;
  }

  /**
   * Saves a taco as a favorite, or confirms it is already saved. A taco that
   * does not exist is a 404: a favorite is a reference to a real thing, and
   * storing a dangling id would be a lie the customer finds out about later.
   */
  public Mono<FavoriteResponse> add(String userId, String tacoId) {
    return tacos.findById(tacoId)
        .switchIfEmpty(Mono.error(new TacoNotFoundException(tacoId)))
        .flatMap(taco -> favorites.findByUserIdAndTacoId(userId, tacoId)
            .map(existing -> FavoriteResponse.of(existing, taco))
            .switchIfEmpty(Mono.defer(() -> insert(userId, taco))));
  }

  /**
   * Removes a favorite. Answering "removed" whether or not it existed is the
   * point of DELETE here: retrying a removal must never produce an error the
   * client has to special-case.
   */
  public Mono<Void> remove(String userId, String tacoId) {
    return favorites.deleteByUserIdAndTacoId(userId, tacoId).then();
  }

  public Mono<PageResponse<FavoriteResponse>> list(String userId, int page, int size) {
    return Mono.zip(
        favorites.countByUserId(userId),
        favorites.findByUserIdOrderBySavedAtDescIdAsc(userId,
            PageRequest.of(page, size)).collectList())
        .flatMap(tally -> withNames(tally.getT2())
            .map(items -> PageResponse.of(items, page, size, tally.getT1())));
  }

  private Mono<FavoriteResponse> insert(String userId, Taco taco) {
    return favorites.save(new Favorite(userId, taco.getId()))
        .map(saved -> FavoriteResponse.of(saved, taco))
        .onErrorResume(DuplicateKeyException.class, duplicate ->
            // Another request inserted the same pair first. Its row is the
            // favorite; nothing about it needs to change.
            favorites.findByUserIdAndTacoId(userId, taco.getId())
                .map(existing -> FavoriteResponse.of(existing, taco))
                .switchIfEmpty(Mono.error(duplicate)));
  }

  /**
   * Resolves the taco names for one page of favorites with a single query, so
   * a page of twenty favorites is twenty-one round trips at worst instead of
   * twenty times the size of the page.
   */
  private Mono<List<FavoriteResponse>> withNames(List<Favorite> page) {
    if (page.isEmpty()) {
      return Mono.just(List.of());
    }
    List<String> tacoIds = page.stream()
        .map(Favorite::getTacoId)
        .distinct()
        .collect(Collectors.toList());
    return tacos.findAllById(tacoIds)
        .collectMap(Taco::getId, taco -> taco)
        .map(catalog -> page.stream()
            .map(favorite -> FavoriteResponse.of(favorite, catalog.get(favorite.getTacoId())))
            .collect(Collectors.toList()));
  }

}
