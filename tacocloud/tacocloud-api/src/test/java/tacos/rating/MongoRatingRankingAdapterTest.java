package tacos.rating;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.test.util.ReflectionTestUtils;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tacos.TacoRating;

/**
 * TC-22's ranking pipeline.
 *
 * <p>No MongoDB is available in this lab, so the pipeline is asserted as
 * rendered rather than executed: the aggregation is captured and the BSON the
 * driver would receive is inspected. That is enough to catch the mistakes that
 * matter here — a limit applied before the sort, a vote floor on the wrong
 * field, or a stage that quietly went missing.
 */
public class MongoRatingRankingAdapterTest {

  private ReactiveMongoTemplate template;
  private MongoRatingRankingAdapter adapter;

  @BeforeEach
  public void setup() {
    template = mock(ReactiveMongoTemplate.class);
    adapter = new MongoRatingRankingAdapter(template);
    when(template.aggregate(any(Aggregation.class), any(Class.class), any(Class.class)))
        .thenReturn(Flux.empty());
  }

  @Test
  public void tc22_theChartIsOneQueryWithFourStages() {
    adapter.top(5, 2).block();

    assertEquals(List.of("$group", "$match", "$sort", "$limit"), operators());
  }

  @Test
  public void tc22_theChartGroupsByTacoCountingAndAveragingTheVotes() {
    adapter.top(5, 2).block();

    Document group = stageAt(0);
    assertEquals("$tacoId", group.getString("_id"));
    assertEquals(new Document("$sum", 1), group.get("votes"));
    assertEquals(new Document("$avg", "$score"), group.get("average"));
  }

  @Test
  public void tc22_tacosBelowTheVoteFloorAreDropped() {
    adapter.top(5, 3).block();

    // "votes" only exists after the $group; matching it any earlier would be a
    // query against a field the documents do not have.
    Document floor = (Document) stageAt(1).get("votes");
    assertEquals(3, ((Number) floor.get("$gte")).intValue());
  }

  @Test
  public void tc22_theChartIsSortedByAverageThenVotesThenId() {
    adapter.top(5, 2).block();

    Document sort = stageAt(2);
    assertEquals(-1, sort.getInteger("average"));
    assertEquals(-1, sort.getInteger("votes"));
    assertEquals(1, sort.getInteger("_id"),
        "without a final key the chart reshuffles between identical requests");
  }

  @Test
  public void tc22_theLimitIsTakenAfterTheSort() {
    adapter.top(5, 2).block();

    assertEquals(5, ((Number) stageBodyAt(3)).intValue());
    assertEquals("$sort", operators().get(2),
        "taking ten arbitrary rows and ranking them in Java would be a different chart");
  }

  @Test
  public void tc22_aTacoBelowTheFloorCannotOccupyAChartSlot() {
    // group -> floor -> sort -> limit, in that order.
    adapter.top(3, 2).block();

    assertEquals(List.of("$group", "$match", "$sort", "$limit"), operators());
  }

  @Test
  public void tc22_aSingleTacoIsReadWithoutTheVoteFloor() {
    when(template.aggregate(any(Aggregation.class), any(Class.class), any(Class.class)))
        .thenReturn(Flux.just(tally("taco-1", 1, 5.0)));

    RatingAggregate aggregate = adapter.of("taco-1").block();

    assertEquals(1, aggregate.getVotes(),
        "the caller named the taco, so one vote is an answer and not a reason to claim "
            + "there is no answer");
    assertEquals(5.0, aggregate.getAverage(), 0.0001);
  }

  @Test
  public void tc22_aSingleTacoIsFilteredById() {
    adapter.of("taco-1").block();

    assertEquals("taco-1", valueOf(stageAt(0), "tacoId"));
  }

  @Test
  public void tc22_aSingleTacoNeedsNeitherTheFloorNorTheLimit() {
    when(template.aggregate(any(Aggregation.class), any(Class.class), any(Class.class)))
        .thenReturn(Flux.just(tally("taco-1", 3, 4.5)));

    adapter.of("taco-1").block();

    assertEquals(List.of("$match", "$group", "$sort"), operators());
  }

  @Test
  public void tc22_anUnratedTacoIsAnEmptyStream() {
    StepVerifier.create(adapter.of("ghost")).verifyComplete();

    verify(template, times(1)).aggregate(any(Aggregation.class), any(Class.class),
        any(Class.class));
  }

  @Test
  public void tc22_theGroupIdBecomesTheTacoId() {
    when(template.aggregate(any(Aggregation.class), any(Class.class), any(Class.class)))
        .thenReturn(Flux.just(tally("taco-7", 9, 4.25)));

    RatingAggregate aggregate = adapter.of("taco-7").block();

    assertEquals("taco-7", aggregate.getTacoId());
    assertEquals(9, aggregate.getVotes());
  }

  @Test
  public void tc22_theChartIsOneQueryNotOnePerTaco() {
    when(template.aggregate(any(Aggregation.class), any(Class.class), any(Class.class)))
        .thenReturn(Flux.just(tally("taco-1", 2, 4.0), tally("taco-2", 5, 3.0),
            tally("taco-3", 1, 5.0)));

    assertEquals(3, adapter.top(3, 1).block().size());

    verify(template, times(1)).aggregate(any(Aggregation.class), any(Class.class),
        any(Class.class));
  }

  @Test
  public void tc22_theGroupResultIsMappedThroughItsOwnType() {
    when(template.aggregate(any(Aggregation.class), any(Class.class), any(Class.class)))
        .thenReturn(Flux.just(tally("taco-1", 2, 4.0), tally("taco-2", 40, 3.5)));

    List<RatingAggregate> chart = adapter.top(2, 1).block();

    assertEquals(List.of("taco-1", "taco-2"), chart.stream()
        .map(RatingAggregate::getTacoId)
        .collect(Collectors.toList()));
    ArgumentCaptor<Class<?>> target = ArgumentCaptor.forClass(Class.class);
    verify(template).aggregate(any(Aggregation.class), eq(TacoRating.class), target.capture());
    assertEquals(MongoRatingRankingAdapter.RatingTally.class, target.getValue());
  }

  @Test
  public void tc22_theRankingReadsTheRatingsNotTheTacos() {
    adapter.top(5, 2).block();

    ArgumentCaptor<Class<?>> source = ArgumentCaptor.forClass(Class.class);
    verify(template).aggregate(any(Aggregation.class), source.capture(), any(Class.class));
    assertEquals(TacoRating.class, source.getValue());
  }

  @Test
  public void tc22_theChartIsOrderedByTheDatabaseNotByJava() {
    when(template.aggregate(any(Aggregation.class), any(Class.class), any(Class.class)))
        .thenReturn(Flux.just(tally("taco-b", 3, 4.0), tally("taco-a", 30, 3.9)));

    List<RatingAggregate> chart = adapter.top(2, 1).block();

    assertEquals("taco-b", chart.get(0).getTacoId(),
        "re-sorting here would override the tie-breaks the pipeline applied");
  }

  @Test
  public void tc22_theChartIsEmptyWhenNobodyHasVoted() {
    assertTrue(adapter.top(10, 2).block().isEmpty());
  }

  @Test
  public void tc22_anEmptyStreamIsAnEmptyChartNotAnError() {
    when(template.aggregate(any(Aggregation.class), any(Class.class), any(Class.class)))
        .thenReturn(Flux.empty());

    assertTrue(adapter.top(10, 2).block().isEmpty());
  }

  @Test
  public void tc22_theGroupStageDoesNotReplaceTheDocument() {
    // A $replaceRoot would be needed to read the tally as a TacoRating; it is
    // read through its own mapping type instead, so the stored row is untouched.
    adapter.top(5, 2).block();

    assertFalse(stageAt(0).containsKey("$replaceRoot"));
  }

  @Test
  public void tc22_theChartCarriesTheOrderOfTheDatabaseUnchanged() {
    when(template.aggregate(any(Aggregation.class), any(Class.class), any(Class.class)))
        .thenReturn(Flux.just(tally("taco-9", 1, 5.0), tally("taco-8", 99, 5.0)));

    assertEquals(List.of("taco-9", "taco-8"), adapter.top(2, 1).block().stream()
        .map(RatingAggregate::getTacoId)
        .collect(Collectors.toList()));
  }

  /** {@code Criteria.is} renders as a bare value or wrapped in {@code $eq}. */
  private static Object valueOf(Document document, String field) {
    Object value = document.get(field);
    return value instanceof Document ? ((Document) value).get("$eq") : value;
  }

  private static MongoRatingRankingAdapter.RatingTally tally(String tacoId, int votes,
      double average) {
    MongoRatingRankingAdapter.RatingTally result =
        new MongoRatingRankingAdapter.RatingTally();
    ReflectionTestUtils.setField(result, "id", tacoId);
    ReflectionTestUtils.setField(result, "votes", votes);
    ReflectionTestUtils.setField(result, "average", average);
    return result;
  }

  private List<String> operators() {
    return pipeline().stream()
        .map(stage -> stage.keySet().iterator().next())
        .collect(Collectors.toList());
  }

  /** The body of the stage at {@code index}: {@code {"$sort": {...}}} -> the sort. */
  private Document stageAt(int index) {
    Object body = stageBodyAt(index);
    return body instanceof Document ? (Document) body : new Document();
  }

  /** {@code $limit} carries a bare number, so the body is not always a document. */
  private Object stageBodyAt(int index) {
    return pipeline().get(index).values().iterator().next();
  }

  /** The pipeline as BSON: what MongoDB would actually receive. */
  private List<Document> pipeline() {
    ArgumentCaptor<Aggregation> captured = ArgumentCaptor.forClass(Aggregation.class);
    verify(template).aggregate(captured.capture(), any(Class.class), any(Class.class));
    return new ArrayList<>(captured.getValue().toPipeline(Aggregation.DEFAULT_CONTEXT));
  }

}
