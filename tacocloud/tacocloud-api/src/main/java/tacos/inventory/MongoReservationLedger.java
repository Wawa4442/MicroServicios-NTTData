package tacos.inventory;

import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/**
 * {@link ReservationLedger} backed by MongoDB. {@code confirm} only transitions
 * a {@code RESERVED} reservation, {@code release} only transitions a still
 * valid one ({@code RESERVED} or {@code CONFIRMED}). Both are single conditional
 * {@code updateFirst} calls: a release can never happen twice, and only a
 * deliberate cancellation/deletion returns the stock of a confirmed order.
 */
@Component
public class MongoReservationLedger implements ReservationLedger {

  private final ReactiveMongoTemplate template;

  public MongoReservationLedger(ReactiveMongoTemplate template) {
    this.template = template;
  }

  @Override
  public Mono<StockReservation> findByKey(String reservationKey) {
    return template.findOne(Query.query(Criteria.where("reservationKey").is(reservationKey)),
        StockReservation.class);
  }

  @Override
  public Mono<StockReservation> findByOrderId(String orderId) {
    return template.findOne(Query.query(Criteria.where("orderId").is(orderId)),
        StockReservation.class);
  }

  @Override
  public Mono<StockReservation> save(StockReservation reservation) {
    return template.save(reservation).map(r -> r);
  }

  @Override
  public Mono<Boolean> confirm(String reservationKey, String orderId) {
    Query query = Query.query(Criteria.where("reservationKey").is(reservationKey)
        .and("status").is(ReservationStatus.RESERVED));
    Update update = new Update()
        .set("status", ReservationStatus.CONFIRMED)
        .set("orderId", orderId);
    return template.updateFirst(query, update, StockReservation.class)
        .map(result -> result.getMatchedCount() == 1);
  }

  @Override
  public Mono<Boolean> release(String reservationKey) {
    Query query = Query.query(Criteria.where("reservationKey").is(reservationKey)
        .and("status").in(ReservationStatus.RESERVED, ReservationStatus.CONFIRMED));
    Update update = new Update().set("status", ReservationStatus.RELEASED);
    return template.updateFirst(query, update, StockReservation.class)
        .map(result -> result.getMatchedCount() == 1);
  }

}