package tacos.search;

import reactor.core.publisher.Mono;
import tacos.Taco;

/**
 * Where catalog searches actually run (TC-19). The service talks to this port,
 * not to MongoDB, so the query semantics can be tested without a database and
 * an alternative backend would not touch the controller.
 */
public interface TacoSearchPort {

  /**
   * Runs the search server-side. Implementations must filter and page in the
   * database; returning "everything and let the caller filter" would defeat the
   * whole point of the endpoint.
   */
  Mono<org.springframework.data.domain.Page<Taco>> search(TacoSearchQuery query);

}
