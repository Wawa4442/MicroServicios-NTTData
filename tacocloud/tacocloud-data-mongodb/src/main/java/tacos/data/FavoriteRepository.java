package tacos.data;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Favorite;

/**
 * Favorites (TC-21).
 *
 * <p>Every read is scoped by {@code userId} in the query itself, so "my
 * favorites" can never be implemented by loading everything and filtering in
 * memory. {@code savedAt} plus {@code _id} keeps paging stable when two
 * favorites share a timestamp.
 *
 * <p>The repository is <b>not</b> exported through Spring Data REST: it carries
 * a customer identifier, and the Data REST surface is a generic CRUD API that
 * would happily hand out every favorite to whoever asks.
 */
@RepositoryRestResource(exported = false)
public interface FavoriteRepository extends ReactiveMongoRepository<Favorite, String> {

  Flux<Favorite> findByUserIdOrderBySavedAtDescIdAsc(String userId, Pageable pageable);

  Mono<Favorite> findByUserIdAndTacoId(String userId, String tacoId);

  Mono<Long> countByUserId(String userId);

  /**
   * Idempotent by construction: deleting a favorite that is not there simply
   * matches nothing, and the caller still answers "removed".
   */
  Mono<Void> deleteByUserIdAndTacoId(String userId, String tacoId);

}
