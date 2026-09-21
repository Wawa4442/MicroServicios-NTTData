package tacos.inventory;

import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;
import tacos.Ingredient;

/**
 * {@link StockPort} backed by MongoDB single-document atomic updates (TC-16).
 *
 * <p>{@code tryDebit} is one {@code updateFirst} whose filter requires
 * {@code stockOnHand >= quantity} and {@code available = true}, and whose
 * mutation is a single {@code $inc}. MongoDB applies the filter and the
 * mutation to the same document in one atomic step, so two concurrent debits
 * cannot oversell a unit. This is explicitly <em>not</em> a read-modify-write.
 */
@Component
public class MongoStockPort implements StockPort {

  private final ReactiveMongoTemplate template;

  public MongoStockPort(ReactiveMongoTemplate template) {
    this.template = template;
  }

  @Override
  public Mono<Boolean> tryDebit(String ingredientId, int quantity) {
    Query query = Query.query(Criteria.where("_id").is(ingredientId)
        .and("stockOnHand").gte(quantity)
        .and("available").is(true));
    Update update = new Update().inc("stockOnHand", -quantity);
    return template.updateFirst(query, update, Ingredient.class)
        .map(result -> result.getMatchedCount() == 1);
  }

  @Override
  public Mono<Void> credit(String ingredientId, int quantity) {
    Query query = Query.query(Criteria.where("_id").is(ingredientId));
    Update update = new Update().inc("stockOnHand", quantity);
    return template.updateFirst(query, update, Ingredient.class).then();
  }

}