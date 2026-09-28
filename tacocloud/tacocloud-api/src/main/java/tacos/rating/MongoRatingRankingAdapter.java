package tacos.rating;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.TacoRating;

/**
 * {@link RatingRankingPort} as a MongoDB aggregation (TC-22).
 *
 * <p>One pipeline, one round trip: group the votes by taco, count and average
 * them, drop the tacos below the minimum vote count, sort, and only then take
 * the top N. Ordering the limit after the sort is the whole reason this is not
 * a repository method — taking ten arbitrary rows and ranking them in Java
 * would produce a different, and wrong, chart.
 *
 * <p>Ties are broken deterministically, twice: by vote count first, so a
 * five-star taco with ten votes ranks above a five-star taco with two, and by
 * id last, so two tacos that tie on both always come back in the same order.
 * Without that final key the chart would reshuffle between identical requests.
 */
@Component
public class MongoRatingRankingAdapter implements RatingRankingPort {

  private final ReactiveMongoTemplate template;

  public MongoRatingRankingAdapter(ReactiveMongoTemplate template) {
    this.template = template;
  }

  @Override
  public Mono<List<RatingAggregate>> top(int limit, int minVotes) {
    return tally(tacoIdFilter(null), minVotes, limit).collectList();
  }

  @Override
  public Mono<RatingAggregate> of(String tacoId) {
    // No vote floor here: the caller named the taco, so "only one person has
    // rated it" is the answer, not a reason to claim there is no answer.
    return tally(tacoIdFilter(tacoId), null, null).next();
  }

  private Criteria tacoIdFilter(String tacoId) {
    return tacoId == null ? null : Criteria.where("tacoId").is(tacoId);
  }

  private Flux<RatingAggregate> tally(
      Criteria tacoFilter, Integer minVotes, Integer limit) {
    List<AggregationOperation> stages = new ArrayList<>();
    if (tacoFilter != null) {
      stages.add(Aggregation.match(tacoFilter));
    }
    stages.add(Aggregation.group("tacoId").count().as("votes").avg("score").as("average"));
    if (minVotes != null) {
      stages.add(Aggregation.match(Criteria.where("votes").gte(minVotes)));
    }
    stages.add(context -> new Document("$sort", new Document("average", -1)
        .append("votes", -1)
        .append("_id", 1)));
    if (limit != null) {
      stages.add(Aggregation.limit(limit));
    }
    return template.aggregate(Aggregation.newAggregation(stages),
        TacoRating.class, RatingTally.class).map(RatingTally::toAggregate);
  }

  /** Shape of one group result: {@code _id} is the grouped {@code tacoId}. */
  static class RatingTally {

    @Id
    private String id;
    private int votes;
    private double average;

    RatingAggregate toAggregate() {
      return new RatingAggregate(id, votes, average);
    }
  }

}
