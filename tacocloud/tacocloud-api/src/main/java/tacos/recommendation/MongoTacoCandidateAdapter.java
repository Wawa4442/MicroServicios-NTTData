package tacos.recommendation;

import java.util.List;

import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Taco;

/**
 * {@link TacoCandidatePort} on MongoDB (TC-20).
 *
 * <p>The interesting part is what "sellable" is measured against. A taco
 * document embeds a <em>snapshot</em> of its ingredients taken when it was
 * designed, so the copy inside the taco is stale the moment the catalog
 * changes and cannot be trusted to say whether the taco can still be sold.
 * The authority is the ingredient collection, so this adapter first asks which
 * ingredients are out of sale or out of stock and then excludes every taco
 * holding one of them.
 *
 * <p>Only {@code _id}, {@code name} and {@code ingredients} are read back, and
 * the ids are collected before the taco query so no candidate is ever
 * materialized in the service just to be discarded.
 */
@Component
public class MongoTacoCandidateAdapter implements TacoCandidatePort {

  private final ReactiveMongoTemplate template;

  public MongoTacoCandidateAdapter(ReactiveMongoTemplate template) {
    this.template = template;
  }

  @Override
  public Flux<Taco> sellableCandidates() {
    return notSellableIngredientIds()
        .flatMapMany(blocked -> template.find(tacosWithout(blocked), Taco.class));
  }

  @Override
  public Mono<Taco> sellableCandidate(String tacoId) {
    return notSellableIngredientIds()
        .flatMap(blocked -> template.findOne(tacoWithout(blocked, tacoId), Taco.class));
  }

  /**
   * Ids the catalog has taken off the menu. A commercial pause
   * ({@code available = false}) and an empty shelf ({@code stockOnHand <= 0})
   * both disqualify an ingredient, and therefore every taco that uses it.
   */
  private Mono<List<String>> notSellableIngredientIds() {
    Query blocked = Query.query(new Criteria().orOperator(
        Criteria.where("available").is(false),
        Criteria.where("stockOnHand").lte(0)));
    blocked.fields().include("_id");
    return template.find(blocked, Ingredient.class)
        .map(Ingredient::getId)
        .collectList();
  }

  private Query tacosWithout(List<String> blockedIngredientIds) {
    Query query = new Query();
    if (!blockedIngredientIds.isEmpty()) {
      query.addCriteria(Criteria.where("ingredients._id")
          .nin(blockedIngredientIds));
    }
    query.fields().include("_id").include("name").include("ingredients");
    return query;
  }

  private Query tacoWithout(List<String> blockedIngredientIds, String tacoId) {
    Query query = tacosWithout(blockedIngredientIds);
    query.addCriteria(Criteria.where("_id").is(tacoId));
    return query;
  }

}
