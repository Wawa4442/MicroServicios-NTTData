package tacos.rating;

import java.util.List;

import reactor.core.publisher.Mono;

/**
 * Where the ranking is computed (TC-22). A port rather than a repository method
 * because the answer is an aggregation, and "load every rating and group in
 * Java" would quietly turn a fixed cost into one that grows with the number of
 * votes in the system.
 */
public interface RatingRankingPort {

  /**
   * Best rated tacos first, already filtered by the minimum vote count and
   * already truncated to {@code limit}. Sorting has to happen before the limit,
   * so it is the database's job, not the caller's.
   */
  Mono<List<RatingAggregate>> top(int limit, int minVotes);

  /** The same aggregate for a single taco, or empty when nobody rated it. */
  Mono<RatingAggregate> of(String tacoId);

}
