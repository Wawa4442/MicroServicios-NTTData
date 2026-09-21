package tacos.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;

/**
 * Pins the MongoDB <em>contract</em> behind {@link InventoryService}: the
 * debit must be a single atomic {@code updateFirst} carrying the inventory
 * guard inside the query (no read-modify-write), the credit a plain
 * increment. This is what makes concurrent reservations safe below the
 * service layer.
 */
public class MongoStockPortTest {

  private static final String FLTO = "FLTO";

  private ReactiveMongoTemplate template;
  private MongoStockPort port;

  @BeforeEach
  public void setup() {
    template = mock(ReactiveMongoTemplate.class);
    port = new MongoStockPort(template);
  }

  @SuppressWarnings("unchecked")
  private Mono<com.mongodb.client.result.UpdateResult>
      matchedUpdateResult(long matches) {
    return Mono.just(com.mongodb.client.result.UpdateResult
        .acknowledged(matches, matches, null));
  }

  @Test
  public void tryDebit_isASingleAtomicUpdate_withStockGuardInTheQuery() {
    when(template.updateFirst(any(Query.class), any(Update.class),
        eq(Ingredient.class))).thenReturn(matchedUpdateResult(1));

    StepVerifier.create(port.tryDebit(FLTO, 3))
        .expectNext(true)
        .verifyComplete();

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    org.mockito.Mockito.verify(template).updateFirst(
        query.capture(), update.capture(), eq(Ingredient.class));

    Document q = query.getValue().getQueryObject();
    Document updateDoc = update.getValue().getUpdateObject();

    assertEquals(FLTO, q.get("_id"));
    assertEquals(3, ((Document) q.get("stockOnHand")).get("$gte"));
    assertEquals(Boolean.TRUE, q.get("available"));
    assertEquals(-3, ((Document) updateDoc.get("$inc")).get("stockOnHand"));
  }

  @Test
  public void tryDebit_whenNoDocumentMatches_returnsFalse() {
    when(template.updateFirst(any(Query.class), any(Update.class),
        eq(Ingredient.class))).thenReturn(matchedUpdateResult(0));

    StepVerifier.create(port.tryDebit(FLTO, 3))
        .expectNext(false)
        .verifyComplete();
  }

  @Test
  public void credit_isAPlainIncrement_withNoGuardCondition() {
    when(template.updateFirst(any(Query.class), any(Update.class),
        eq(Ingredient.class))).thenReturn(matchedUpdateResult(1));

    StepVerifier.create(port.credit(FLTO, 4)).verifyComplete();

    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    org.mockito.Mockito.verify(template).updateFirst(
        query.capture(), update.capture(), eq(Ingredient.class));

    Document q = query.getValue().getQueryObject();
    Document updateDoc = update.getValue().getUpdateObject();

    assertEquals(FLTO, q.get("_id"));
    assertEquals(0, q.keySet().size() - 1, "credit guards on the id only");
    assertEquals(4, ((Document) updateDoc.get("$inc")).get("stockOnHand"));
  }

}