package tacos.kitchen;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import tacos.OrderStatus;
import tacos.OrderStatusChange;
import tacos.TacoOrder;
import tacos.api.dto.PageResponse;
import tacos.data.OrderRepository;
import tacos.paging.PageBounds;
import tacos.web.api.CallerIdentity;

/**
 * Kitchen queue use cases (TC-26).
 *
 * <p>Listing is a filtered, paged query over {@code CREATED} ordered by
 * {@code placedAt, _id}: the FIFO lives in the database, never in memory.
 * Claiming is a single atomic {@code findAndModify} conditioned on
 * {@code status = CREATED}: two stations racing for the same ticket cannot
 * both win, because Mongo decides the winner. There is deliberately no
 * read-then-save here.
 */
@Service
public class KitchenQueueService {

  private final OrderRepository orders;
  private final ReactiveMongoTemplate mongo;
  private final EtaCalculator eta;
  private final KitchenProperties props;

  public KitchenQueueService(OrderRepository orders, ReactiveMongoTemplate mongo,
                             EtaCalculator eta, KitchenProperties props) {
    this.orders = orders;
    this.mongo = mongo;
    this.eta = eta;
    this.props = props;
  }

  /**
   * Waiting tickets, oldest first, as a page.
   */
  public Mono<PageResponse<KitchenOrderResponse>> queue(Integer page, Integer size) {
    PageBounds bounds = PageBounds.of(page, size,
        props.getDefaultPageSize(), props.getMaxPageSize());
    PageRequest window = PageRequest.of(bounds.getPage(), bounds.getSize(),
        Sort.by(Sort.Direction.ASC, "placedAt", "_id"));
    Mono<Long> total = orders.countByStatus(OrderStatus.CREATED);
    return orders.findByStatusOrderByPlacedAtAscIdAsc(OrderStatus.CREATED, window)
        .collectList()
        .zipWith(total)
        .map(pair -> {
          List<TacoOrder> rows = pair.getT1();
          long count = pair.getT2();
          List<KitchenOrderResponse> content = rows.stream()
              .map(order -> KitchenOrderResponse.of(order,
                  eta.estimateMinutes(order, count)))
              .collect(Collectors.toList());
          return PageResponse.of(content, bounds.getPage(), bounds.getSize(), count);
        });
  }

  /**
   * Atomically takes the oldest {@code CREATED} order to {@code ACCEPTED}
   * and stamps the station/cook that won it.
   *
   * @param stationId optional; falls back to the caller label.
   * @param cookId optional; falls back to the caller label.
   */
  public Mono<KitchenOrderResponse> claimNext(String stationId, String cookId,
                                             CallerIdentity caller) {
    String station = stationId == null || stationId.trim().isEmpty()
        ? caller.auditLabel() : stationId.trim();
    String cook = cookId == null || cookId.trim().isEmpty()
        ? caller.auditLabel() : cookId.trim();
    Query query = new Query(Criteria.where("status").is(OrderStatus.CREATED))
        .with(Sort.by(Sort.Direction.ASC, "placedAt", "_id"));
    OrderStatusChange change = new OrderStatusChange(OrderStatus.CREATED,
        OrderStatus.ACCEPTED, caller.auditLabel(), new Date(), "KITCHEN",
        "Claimed by " + station);
    Update update = new Update()
        .set("status", OrderStatus.ACCEPTED)
        .set("stationId", station)
        .set("cookId", cook)
        .push("statusHistory", change);
    FindAndModifyOptions options = FindAndModifyOptions.options().returnNew(true);
    return mongo.findAndModify(query, update, options, TacoOrder.class)
        .switchIfEmpty(Mono.error(new KitchenQueueEmptyException()))
        .flatMap(claimed -> orders.countByStatus(OrderStatus.CREATED)
            .map(depth -> KitchenOrderResponse.of(claimed,
                eta.estimateMinutes(claimed, depth)))
            .defaultIfEmpty(KitchenOrderResponse.of(claimed,
                eta.estimateMinutes(claimed, 0))));
  }
}
