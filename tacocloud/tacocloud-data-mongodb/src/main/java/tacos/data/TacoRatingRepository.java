package tacos.data;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

import reactor.core.publisher.Mono;
import tacos.TacoRating;

/**
 * Ratings (TC-22). Single-row access is all the repository needs; the ranking
 * is an aggregation and lives in the API module behind
 * {@code tacos.rating.RatingRankingPort}, so the query never degrades into one
 * read per taco.
 *
 * <p>Not exported through Spring Data REST: a rating row identifies its author
 * and the generic CRUD surface has no ownership rule to enforce.
 */
@RepositoryRestResource(exported = false)
public interface TacoRatingRepository extends ReactiveMongoRepository<TacoRating, String> {

  Mono<TacoRating> findByUserIdAndTacoId(String userId, String tacoId);

  Mono<Long> countByTacoId(String tacoId);

}
