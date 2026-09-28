package tacos.rating;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Taco;
import tacos.TacoRating;
import tacos.data.IngredientRepository;
import tacos.data.TacoRatingRepository;
import tacos.data.TacoRepository;
import tacos.web.api.TacoNotFoundException;

/**
 * TC-22: one vote per customer, changeable, and never counted twice.
 *
 * <p>The repository is a mock on purpose. The behaviour these cases pin is
 * "the customer's own row is updated instead of a new one being added", and that
 * is decided by what the service hands to the repository — a mock is the only
 * way to see it. The query that reads the votes back is covered separately in
 * {@code MongoRatingRankingAdapterTest}.
 */
public class TacoRatingServiceTest {

  private static final String ALICE = "userA";
  private static final String BOB = "userB";

  private TacoRatingRepository ratings;
  private TacoRepository tacos;
  private IngredientRepository ingredients;
  private RatingRankingPort ranking;
  private RatingProperties properties;
  private TacoRatingService service;

  @BeforeEach
  public void setup() {
    ratings = mock(TacoRatingRepository.class);
    tacos = mock(TacoRepository.class);
    ingredients = mock(IngredientRepository.class);
    ranking = mock(RatingRankingPort.class);
    properties = new RatingProperties();
    service = new TacoRatingService(ratings, tacos, ingredients, ranking, properties);

    when(tacos.findById("taco-1")).thenReturn(Mono.just(taco("taco-1", "FLTO", "PORK")));
    when(tacos.findById("ghost")).thenReturn(Mono.empty());
    when(ingredients.findAllById(any(Iterable.class)))
        .thenAnswer(call -> {
          Iterable<String> ids = call.getArgument(0);
          return Flux.fromIterable(ids).map(TacoRatingServiceTest::availableIngredient);
        });
    when(ratings.save(any(TacoRating.class)))
        .thenAnswer(call -> Mono.just(call.getArgument(0)));
    when(ratings.countByTacoId(anyString())).thenReturn(Mono.just(1L));
    when(tacos.findAllById(anyIds())).thenReturn(Flux.empty());
  }

  @Test
  public void tc22_aFirstVoteCreatesOneRow() {
    when(ratings.findByUserIdAndTacoId(ALICE, "taco-1")).thenReturn(Mono.empty());
    when(ranking.of("taco-1")).thenReturn(Mono.just(new RatingAggregate("taco-1", 1, 4.0)));

    StepVerifier.create(service.rate(ALICE, "taco-1", 4))
        .assertNext(response -> {
          assertEquals("taco-1", response.getTacoId());
          assertEquals(4, response.getScore());
          assertEquals(1, response.getVotes());
          assertEquals("4.00", response.getAverageScore().toString());
        })
        .verifyComplete();

    ArgumentCaptor<TacoRating> saved = ArgumentCaptor.forClass(TacoRating.class);
    verify(ratings, times(1)).save(saved.capture());
    assertEquals(ALICE, saved.getValue().getUserId());
    assertEquals("taco-1", saved.getValue().getTacoId());
  }

  @Test
  public void tc22_changingYourMindUpdatesYourRowInsteadOfAddingOne() {
    TacoRating existing = new TacoRating(ALICE, "taco-1", 2);
    when(ratings.findByUserIdAndTacoId(ALICE, "taco-1")).thenReturn(Mono.just(existing));
    when(ranking.of("taco-1")).thenReturn(Mono.just(new RatingAggregate("taco-1", 1, 5.0)));

    StepVerifier.create(service.rate(ALICE, "taco-1", 5))
        .assertNext(response -> assertEquals(5, response.getScore()))
        .verifyComplete();

    verify(ratings, times(1)).save(any(TacoRating.class));
    assertEquals(5, existing.getScore(), "the same row is overwritten");
  }

  @Test
  public void tc22_changingYourMindDoesNotInflateTheVoteCount() {
    TacoRating existing = new TacoRating(ALICE, "taco-1", 2);
    when(ratings.findByUserIdAndTacoId(ALICE, "taco-1")).thenReturn(Mono.just(existing));
    when(ranking.of("taco-1"))
        .thenReturn(Mono.just(new RatingAggregate("taco-1", 1, 5.0)));

    StepVerifier.create(service.rate(ALICE, "taco-1", 5))
        .assertNext(response -> assertEquals(1, response.getVotes(),
            "the number of votes counts customers, not how many times they changed "
                + "their mind"))
        .verifyComplete();
  }

  @Test
  public void tc22_oneCustomerCannotOverwriteAnothersVote() {
    TacoRating bobsRow = new TacoRating(BOB, "taco-1", 1);
    when(ratings.findByUserIdAndTacoId(ALICE, "taco-1")).thenReturn(Mono.empty());
    when(ranking.of("taco-1")).thenReturn(Mono.just(new RatingAggregate("taco-1", 2, 3.0)));

    service.rate(ALICE, "taco-1", 5).block();

    ArgumentCaptor<TacoRating> saved = ArgumentCaptor.forClass(TacoRating.class);
    verify(ratings).save(saved.capture());
    assertEquals(ALICE, saved.getValue().getUserId());
    assertEquals(1, bobsRow.getScore(), "Bob's row is not even loaded, let alone written");
  }

  @Test
  public void tc22_aSimultaneousFirstVoteDoesNotFail() {
    when(ratings.findByUserIdAndTacoId(ALICE, "taco-1"))
        .thenReturn(Mono.empty())
        .thenReturn(Mono.just(new TacoRating(ALICE, "taco-1", 1)));
    AtomicInteger attempts = new AtomicInteger();
    when(ratings.save(any(TacoRating.class))).thenAnswer(call -> {
      TacoRating candidate = call.getArgument(0);
      return attempts.getAndIncrement() == 0
          ? Mono.error(new DuplicateKeyException("rating_user_taco_unique"))
          : Mono.just(candidate);
    });
    when(ranking.of("taco-1")).thenReturn(Mono.just(new RatingAggregate("taco-1", 1, 5.0)));

    StepVerifier.create(service.rate(ALICE, "taco-1", 5))
        .assertNext(response -> assertEquals(5, response.getScore()))
        .verifyComplete();

    verify(ratings, times(2)).save(any(TacoRating.class));
  }

  @Test
  public void tc22_aScoreOutsideTheScaleIsRejected() {
    StepVerifier.create(service.rate(ALICE, "taco-1", 6))
        .expectError(InvalidRatingException.class)
        .verify();
    StepVerifier.create(service.rate(ALICE, "taco-1", 0))
        .expectError(InvalidRatingException.class)
        .verify();

    verify(ratings, never()).save(any(TacoRating.class));
  }

  @Test
  public void tc22_aMissingScoreIsRejected() {
    StepVerifier.create(service.rate(ALICE, "taco-1", null))
        .expectError(InvalidRatingException.class)
        .verify();
  }

  @Test
  public void tc22_theScoreIsValidatedAgainstTheConfiguredScale() {
    properties.setMaxScore(3);

    StepVerifier.create(service.rate(ALICE, "taco-1", 4))
        .expectError(InvalidRatingException.class)
        .verify();
  }

  @Test
  public void tc22_ratingATacoThatDoesNotExistIsA404() {
    StepVerifier.create(service.rate(ALICE, "ghost", 4))
        .expectError(TacoNotFoundException.class)
        .verify();

    verify(ratings, never()).save(any(TacoRating.class));
  }

  @Test
  public void tc22_ratingATacoWithAnUnavailableIngredientIsA404() {
    when(ingredients.findAllById(any(Iterable.class)))
        .thenReturn(Flux.just(availableIngredient("FLTO"),
            unavailableIngredient("PORK")));

    StepVerifier.create(service.rate(ALICE, "taco-1", 4))
        .expectError(TacoNotFoundException.class)
        .verify();

    verify(ratings, never()).save(any(TacoRating.class));
  }

  @Test
  public void tc22_theRankingOnlyAsksForTheConfiguredNumberOfVotes() {
    when(ranking.top(any(Integer.class), any(Integer.class)))
        .thenReturn(Mono.just(List.of()));

    service.top(null).block();

    verify(ranking).top(properties.getDefaultTopLimit(), properties.getMinVotes());
  }

  @Test
  public void tc22_theRankingHonoursAnExplicitLimit() {
    when(ranking.top(any(Integer.class), any(Integer.class)))
        .thenReturn(Mono.just(List.of()));

    service.top(3).block();

    verify(ranking).top(3, properties.getMinVotes());
  }

  @Test
  public void tc22_anAbsurdLimitIsRejected() {
    // top() validates before it touches the database, so a bad limit throws
    // rather than returning an error publisher.
    assertEquals("limit must be at least 1.",
        assertThrows(InvalidRatingException.class, () -> service.top(0)).getMessage());
    assertEquals("limit must not exceed " + properties.getMaxTopLimit() + ".",
        assertThrows(InvalidRatingException.class,
            () -> service.top(properties.getMaxTopLimit() + 1)).getMessage());
  }

  @Test
  public void tc22_theVoteCountIsReadRatherThanAssumedWhenTheAggregationIsBehind() {
    when(ratings.findByUserIdAndTacoId(ALICE, "taco-1"))
        .thenReturn(Mono.just(new TacoRating(ALICE, "taco-1", 2)));
    when(ranking.of("taco-1")).thenReturn(Mono.empty());
    when(ratings.countByTacoId("taco-1")).thenReturn(Mono.just(12L));

    StepVerifier.create(service.rate(ALICE, "taco-1", 5))
        .assertNext(response -> assertEquals(12, response.getVotes(),
            "a taco that already has twelve voters must never be reported as one"))
        .verifyComplete();
  }

  @Test
  public void tc22_theChartKeepsTheOrderTheDatabaseProduced() {
    when(tacos.findAllById(any(Iterable.class)))
        .thenReturn(Flux.just(taco("taco-2", "FLTO"), taco("taco-1", "FLTO")));
    when(ranking.top(any(Integer.class), any(Integer.class)))
        .thenReturn(Mono.just(List.of(
            new RatingAggregate("taco-2", 40, 4.75),
            new RatingAggregate("taco-1", 12, 4.5))));

    StepVerifier.create(service.top(2))
        .assertNext(chart -> {
          assertEquals(List.of("taco-2", "taco-1"),
              chart.stream().map(entry -> entry.getTacoId()).collect(java.util.stream.Collectors.toList()),
              "the service must not re-sort what the aggregation already ordered");
          assertEquals("4.75", chart.get(0).getAverageScore().toString());
          assertEquals(40, chart.get(0).getVotes());
        })
        .verifyComplete();
  }

  @Test
  public void tc22_aTacoDeletedFromTheCatalogStillAppearsWithItsNumbers() {
    when(tacos.findAllById(any(Iterable.class))).thenReturn(Flux.empty());
    when(ranking.top(any(Integer.class), any(Integer.class)))
        .thenReturn(Mono.just(List.of(new RatingAggregate("taco-9", 5, 4.0))));

    StepVerifier.create(service.top(5))
        .assertNext(chart -> {
          assertEquals(1, chart.size());
          assertEquals("taco-9", chart.get(0).getTacoId());
          assertEquals("4.00", chart.get(0).getAverageScore().toString());
        })
        .verifyComplete();
  }

  @Test
  public void tc22_theAverageIsPublishedAtTheConfiguredPrecision() {
    properties.setAverageScale(1);
    when(ranking.top(any(Integer.class), any(Integer.class)))
        .thenReturn(Mono.just(List.of(new RatingAggregate("taco-1", 3, 3.333333d))));

    StepVerifier.create(service.top(1))
        .assertNext(chart -> assertEquals("3.3", chart.get(0).getAverageScore().toString()))
        .verifyComplete();
  }

  @Test
  public void tc22_theChartIsResolvedWithASingleCatalogQuery() {
    when(tacos.findAllById(any(Iterable.class)))
        .thenReturn(Flux.just(taco("taco-1", "FLTO"), taco("taco-2", "FLTO")));
    when(ranking.top(any(Integer.class), any(Integer.class)))
        .thenReturn(Mono.just(List.of(
            new RatingAggregate("taco-1", 4, 4.0),
            new RatingAggregate("taco-2", 9, 3.0))));

    service.top(2).block();

    verify(tacos, times(1)).findAllById(any(Iterable.class));
    verify(tacos, never()).findById("taco-1");
    verify(tacos, never()).findById("taco-2");
  }

  @Test
  public void tc22_anEmptyChartIsAnEmptyListNotAnError() {
    when(ranking.top(any(Integer.class), any(Integer.class))).thenReturn(Mono.just(List.of()));

    StepVerifier.create(service.top(null))
        .assertNext(chart -> {
          assertTrue(chart.isEmpty());
          verify(tacos, never()).findAllById(any(Iterable.class));
        })
        .verifyComplete();
  }

  @Test
  public void tc22_theAvailabilityCheckDoesNotTrustTheSnapshotInTheTaco() {
    when(tacos.findById("taco-1")).thenReturn(Mono.just(taco("taco-1", "PORK")));
    when(ingredients.findAllById(any(Iterable.class)))
        .thenReturn(Flux.just(unavailableIngredient("PORK")));

    // The embedded copy still says available, which is what a taco designed
    // last month looks like; the ingredient collection is the live answer.
    StepVerifier.create(service.rate(ALICE, "taco-1", 4))
        .expectError(TacoNotFoundException.class)
        .verify();

    verify(ingredients, times(1)).findAllById(any(Iterable.class));
  }

  private static Taco taco(String id, String... ingredientIds) {
    Taco taco = new Taco();
    taco.setId(id);
    taco.setName("Taco " + id);
    taco.setIngredients(java.util.Arrays.stream(ingredientIds)
        .map(TacoRatingServiceTest::availableIngredient)
        .collect(java.util.stream.Collectors.toList()));
    return taco;
  }

  private static Ingredient availableIngredient(String id) {
    Ingredient ingredient = new Ingredient(id, "Ingredient " + id, Ingredient.Type.WRAP);
    ingredient.setAvailable(true);
    return ingredient;
  }

  private static Ingredient unavailableIngredient(String id) {
    Ingredient ingredient = new Ingredient(id, "Ingredient " + id, Ingredient.Type.WRAP);
    ingredient.setAvailable(false);
    return ingredient;
  }

  @Test
  public void tc22_aTacoWithoutIngredientsCannotBeRated() {
    Taco empty = new Taco();
    empty.setId("taco-1");
    empty.setIngredients(List.of());
    when(tacos.findById("taco-1")).thenReturn(Mono.just(empty));

    StepVerifier.create(service.rate(ALICE, "taco-1", 4))
        .expectError(TacoNotFoundException.class)
        .verify();
  }

  @Test
  public void tc22_noSearchLikeAccessToOtherCustomersVotes() {
    // The service is only ever handed a user id that the controller took from
    // the security context, so it cannot be asked for "everybody's ratings".
    when(ratings.findByUserIdAndTacoId(anyString(), anyString())).thenReturn(Mono.empty());
    when(ranking.of(anyString())).thenReturn(Mono.empty());

    service.rate(ALICE, "taco-1", 3).block();

    verify(ratings, never()).findAllById(anyIds());
  }

  private static Iterable<String> anyIds() {
    return org.mockito.ArgumentMatchers.<Iterable<String>>any();
  }

}
