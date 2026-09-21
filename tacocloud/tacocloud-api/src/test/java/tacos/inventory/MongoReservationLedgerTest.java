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

import com.mongodb.client.result.UpdateResult;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;

/**
 * Pins the reservation ledger's <em>guarded transition</em> contract: confirm
 * matches only RESERVED, release matches RESERVED or CONFIRMED. Mongo applies
 * query and mutation atomically, so absence of the guard on the write side
 * would be a programming error these tests would catch.
 */
public class MongoReservationLedgerTest {

  private ReactiveMongoTemplate template;
  private MongoReservationLedger ledger;

  @BeforeEach
  public void setup() {
    template = mock(ReactiveMongoTemplate.class);
    ledger = new MongoReservationLedger(template);
  }

  @SuppressWarnings("unchecked")
  private Mono<UpdateResult> matchedUpdateResult(long matches) {
    return Mono.just(UpdateResult.acknowledged(matches, matches, null));
  }

  private Document queryDoc() {
    ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
    org.mockito.Mockito.verify(template).updateFirst(
        query.capture(), any(Update.class), eq(StockReservation.class));
    return query.getValue().getQueryObject();
  }

  private Document updateDoc() {
    ArgumentCaptor<Update> update = ArgumentCaptor.forClass(Update.class);
    org.mockito.Mockito.verify(template).updateFirst(
        any(Query.class), update.capture(), eq(StockReservation.class));
    return update.getValue().getUpdateObject();
  }

  @Test
  public void confirm_matchesOnlyReservedReservations() {
    when(template.updateFirst(any(Query.class), any(Update.class),
        eq(StockReservation.class))).thenReturn(matchedUpdateResult(1));

    StepVerifier.create(ledger.confirm("key-1", "order-1"))
        .expectNext(true).verifyComplete();

    Document q = queryDoc();
    Document u = updateDoc();
    assertEquals("key-1", q.get("reservationKey"));
    assertEquals(ReservationStatus.RESERVED, q.get("status"));
    Document set = (Document) u.get("$set");
    assertEquals(ReservationStatus.CONFIRMED, set.get("status"));
    assertEquals("order-1", set.get("orderId"));
  }

  @Test
  public void confirm_whenNoReservedReservationMatches_returnsFalse() {
    when(template.updateFirst(any(Query.class), any(Update.class),
        eq(StockReservation.class))).thenReturn(matchedUpdateResult(0));

    StepVerifier.create(ledger.confirm("key-1", "order-1"))
        .expectNext(false).verifyComplete();
  }

  @Test
  public void release_matchesReservedOrConfirmed() {
    when(template.updateFirst(any(Query.class), any(Update.class),
        eq(StockReservation.class))).thenReturn(matchedUpdateResult(1));

    StepVerifier.create(ledger.release("key-1"))
        .expectNext(true).verifyComplete();

    Document q = queryDoc();
    assertEquals("key-1", q.get("reservationKey"));
    assertEquals(ReservationStatus.RELEASED,
        ((Document) updateDoc().get("$set")).get("status"));
    Document status = (Document) q.get("status");
    assertEquals(java.util.List.of(ReservationStatus.RESERVED, ReservationStatus.CONFIRMED),
        status.get("$in"),
        "release must cover RESERVED and CONFIRMED, and nothing else");
  }

  @Test
  public void release_whenAlreadyReleased_returnsFalse() {
    when(template.updateFirst(any(Query.class), any(Update.class),
        eq(StockReservation.class))).thenReturn(matchedUpdateResult(0));

    StepVerifier.create(ledger.release("key-1"))
        .expectNext(false).verifyComplete();
  }

}