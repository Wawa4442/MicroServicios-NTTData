package tacos.recommendation;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Taco;

/**
 * The pool a daily recommendation is drawn from (TC-20).
 *
 * <p>Separated from the recommendation logic so the "which tacos can be
 * recommended at all" rule — available ingredients, positive stock, current
 * catalog state — has one owner and can be tested without a clock.
 */
public interface TacoCandidatePort {

  /**
   * Tacos whose ingredients are all currently sellable, in whatever order the
   * store returns them. Callers must sort before selecting; the store order
   * carries no meaning.
   */
  Flux<Taco> sellableCandidates();

  /** The same rule, narrowed to one id, used to re-check a cached choice. */
  Mono<Taco> sellableCandidate(String tacoId);

}
