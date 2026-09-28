package tacos.rating;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Taco;
import tacos.TacoRating;
import tacos.api.dto.RatingResponse;
import tacos.api.dto.TopTacoResponse;
import tacos.data.IngredientRepository;
import tacos.data.TacoRatingRepository;
import tacos.data.TacoRepository;
import tacos.web.api.TacoNotFoundException;

/**
 * Ratings use cases (TC-22).
 *
 * <p>Two rules carry the weight here.
 *
 * <p><b>One vote per customer, and it can be changed.</b> The unique
 * {@code (userId, tacoId)} index makes "one row" a database fact; changing a
 * mind updates that row instead of adding another, so the vote count does not
 * grow with the number of times somebody changed their mind, and no customer's
 * score can ever be overwritten by another customer's.
 *
 * <p><b>A taco you cannot buy is not rated.</b> A rating is a statement about
 * something on the menu, so the taco has to exist and all of its ingredients
 * have to still be sellable. Availability is re-read from the ingredient
 * collection rather than from the copy embedded in the taco, which is a
 * snapshot from the day the taco was designed.
 */
@Service
public class TacoRatingService {

  private final TacoRatingRepository ratings;
  private final TacoRepository tacos;
  private final IngredientRepository ingredients;
  private final RatingRankingPort ranking;
  private final RatingProperties properties;

  public TacoRatingService(TacoRatingRepository ratings, TacoRepository tacos,
      IngredientRepository ingredients, RatingRankingPort ranking,
      RatingProperties properties) {
    this.ratings = ratings;
    this.tacos = tacos;
    this.ingredients = ingredients;
    this.ranking = ranking;
    this.properties = properties;
  }

  public Mono<RatingResponse> rate(String userId, String tacoId, Integer score) {
    return requireRateableTaco(tacoId)
        .flatMap(taco -> upsert(userId, taco.getId(), validated(score)))
        .flatMap(this::tallyFor);
  }

  /**
   * The taco's tally once the vote is in, read from the same aggregation the
   * ranking uses.
   *
   * <p>When that aggregation comes back empty the write has not reached it yet,
   * which is what a replica lag looks like. The vote count is then counted
   * directly rather than assumed to be one: publishing "1 vote" for a taco that
   * already has twelve would be a number nobody can defend, and the average of
   * an empty aggregation is not an average at all. The customer's own score is
   * the freshest fact available, and the count keeps the response honest about
   * how many people are actually being averaged.
   */
  private Mono<RatingResponse> tallyFor(TacoRating saved) {
    return ranking.of(saved.getTacoId())
        .map(tally -> RatingResponse.of(saved, published(tally.getAverage()),
            tally.getVotes()))
        .switchIfEmpty(ratings.countByTacoId(saved.getTacoId())
            .map(votes -> RatingResponse.of(saved, published(saved.getScore()),
                votes.intValue())));
  }

  /**
   * The chart, best first. Already sorted and truncated by the database; this
   * method only attaches the names and publishes the averages at the
   * configured precision.
   */
  public Mono<List<TopTacoResponse>> top(Integer requestedLimit) {
    int limit = validatedLimit(requestedLimit);
    return ranking.top(limit, properties.getMinVotes())
        .flatMap(this::withNames)
        .map(named -> named.stream()
            .map(entry -> TopTacoResponse.of(entry.tacoId, entry.taco,
                published(entry.tally.getAverage()), entry.tally.getVotes()))
            .collect(Collectors.toList()));
  }

  /**
   * Applies the customer's score, creating the row the first time and
   * overwriting their own row afterwards. A concurrent first vote loses the
   * index race, and is re-read so it updates the winner's row instead of
   * failing.
   */
  private Mono<TacoRating> upsert(String userId, String tacoId, int score) {
    return ratings.findByUserIdAndTacoId(userId, tacoId)
        .map(existing -> {
          existing.setScore(score);
          existing.setUpdatedAt(new Date());
          return existing;
        })
        .switchIfEmpty(Mono.defer(() -> Mono.just(new TacoRating(userId, tacoId, score))))
        .flatMap(ratings::save)
        .onErrorResume(DuplicateKeyException.class, duplicate ->
            ratings.findByUserIdAndTacoId(userId, tacoId)
                .map(winner -> {
                  winner.setScore(score);
                  winner.setUpdatedAt(new Date());
                  return winner;
                })
                .flatMap(ratings::save)
                .switchIfEmpty(Mono.error(duplicate)));
  }

  private Mono<Taco> requireRateableTaco(String tacoId) {
    return tacos.findById(tacoId)
        .switchIfEmpty(Mono.error(new TacoNotFoundException(tacoId)))
        .flatMap(taco -> isOnTheMenu(taco)
            .flatMap(onMenu -> onMenu
                ? Mono.just(taco)
                : Mono.error(new TacoNotFoundException(tacoId))));
  }

  /** True only when every ingredient of the taco is still available for sale. */
  private Mono<Boolean> isOnTheMenu(Taco taco) {
    List<String> ids = taco.getIngredients() == null ? List.of() : taco.getIngredients()
        .stream()
        .filter(ingredient -> ingredient != null && ingredient.getId() != null)
        .map(Ingredient::getId)
        .collect(Collectors.toList());
    if (ids.isEmpty()) {
      return Mono.just(Boolean.FALSE);
    }
    return ingredients.findAllById(ids)
        .filter(Ingredient::isAvailable)
        .count()
        .map(available -> available == ids.size());
  }

  /** Joins the ranking with the catalog in one extra query, not one per taco. */
  private Mono<List<Named>> withNames(List<RatingAggregate> tallies) {
    if (tallies.isEmpty()) {
      return Mono.just(List.of());
    }
    List<String> ids = tallies.stream()
        .map(RatingAggregate::getTacoId)
        .collect(Collectors.toList());
    return tacos.findAllById(ids)
        .collectMap(Taco::getId, taco -> taco)
        .map(catalog -> {
          List<Named> named = new ArrayList<>();
          for (RatingAggregate tally : tallies) {
            named.add(new Named(tally.getTacoId(), catalog.get(tally.getTacoId()), tally));
          }
          return named;
        });
  }

  private int validated(Integer score) {
    if (score == null) {
      throw new InvalidRatingException("A score is required.");
    }
    if (score < properties.getMinScore() || score > properties.getMaxScore()) {
      throw new InvalidRatingException("score must be between " + properties.getMinScore()
          + " and " + properties.getMaxScore() + ".");
    }
    return score;
  }

  private int validatedLimit(Integer requested) {
    int limit = requested == null ? properties.getDefaultTopLimit() : requested;
    if (limit < 1) {
      throw new InvalidRatingException("limit must be at least 1.");
    }
    if (limit > properties.getMaxTopLimit()) {
      throw new InvalidRatingException("limit must not exceed " + properties.getMaxTopLimit() + ".");
    }
    return limit;
  }

  private BigDecimal published(double average) {
    return BigDecimal.valueOf(average)
        .setScale(properties.getAverageScale(), RoundingMode.HALF_UP);
  }

  /** An aggregate joined with the catalog entry that names it. */
  private static final class Named {

    private final String tacoId;
    private final Taco taco;
    private final RatingAggregate tally;

    private Named(String tacoId, Taco taco, RatingAggregate tally) {
      this.tacoId = tacoId;
      this.taco = taco;
      this.tally = tally;
    }
  }

}
