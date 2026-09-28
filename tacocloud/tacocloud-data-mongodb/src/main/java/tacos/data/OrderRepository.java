package tacos.data;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.OrderStatus;
import tacos.TacoOrder;
import tacos.User;

public interface OrderRepository 
         extends ReactiveCrudRepository<TacoOrder, String> {

  Flux<TacoOrder> findByUserOrderByPlacedAtDesc(
          User user, Pageable pageable);

  /**
   * Private order history (TC-23). The owner is part of the query, so paging
   * happens over the customer's own orders instead of over the whole
   * collection. The trailing {@code _id} ordering is what makes page
   * boundaries reproducible when two orders share a {@code placedAt}.
   *
   * <p>The sort is part of the method contract on purpose, so a caller cannot
   * forget it or contradict it: the {@code Pageable} it receives carries the
   * window and nothing else.
   */
  Flux<TacoOrder> findByUser_IdOrderByPlacedAtDescIdDesc(String userId, Pageable pageable);

  Mono<Long> countByUser_Id(String userId);

  /**
   * Operator listing (TC-23): every order, newest first. Written as an
   * explicit query because {@code findAll(pageable)} is not part of
   * {@link ReactiveCrudRepository}, and the ordering has to be stated here for
   * the same reason as in the method above.
   *
   * <p>This is the one method in the codebase that deliberately crosses the
   * "filter by owner" rule, and it exists only for {@code /api/admin/orders},
   * which the security configuration restricts to ADMIN.
   */
  @Query(value = "{}", sort = "{ 'placedAt': -1, '_id': -1 }")
  Flux<TacoOrder> findAllNewestFirst(Pageable pageable);

  /**
   * Kitchen queue (TC-26): orders waiting for a station, oldest first. The
   * trailing {@code _id} keeps the FIFO stable when two orders share a
   * {@code placedAt}, so paging never skips or repeats a row.
   */
  Flux<TacoOrder> findByStatusOrderByPlacedAtAscIdAsc(OrderStatus status, Pageable pageable);

  Mono<Long> countByStatus(OrderStatus status);

}
