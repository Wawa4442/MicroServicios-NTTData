package tacos.recommendation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.rules.TacoValidator;

/**
 * One stable recommendation per business date (TC-20).
 *
 * <p>Determinism comes from three decisions, and from nothing else:
 * <ol>
 *   <li>the date comes from an injected {@link Clock} in a configured zone, so
 *       a test can move to tomorrow without waiting for tomorrow;</li>
 *   <li>candidates are ordered by id before any index is computed, so the
 *       physical order the store returns them in cannot change the answer;</li>
 *   <li>the index is {@code floorMod(epochDay, poolSize)}, which is a pure
 *       function of the date and the pool. Consecutive days therefore advance
 *       the pick by exactly one position, which is predictable without being
 *       random, and a new taco only shifts the rotation when the pool's size
 *       changes the modulus.</li>
 * </ol>
 *
 * <p>The cache remembers one choice per date and nothing more, so memory does
 * not grow with uptime. It is verified rather than trusted: a cached pick is
 * re-checked against the catalog on every read and, if the taco has left the
 * menu, forgotten and recomputed. That is the invalidation rule — the cache can
 * never keep recommending a taco the shop cannot sell.
 */
@Service
public class TacoOfTheDayService {

  /** A taco with no id cannot be ordered, so it cannot anchor a rotation. */
  private static final Comparator<Taco> BY_ID =
      Comparator.comparing(Taco::getId, Comparator.nullsLast(Comparator.naturalOrder()));

  private final TacoCandidatePort candidates;
  private final TacoValidator validator;
  private final Clock clock;
  private final TacoRecommendationProperties properties;
  private final Map<LocalDate, String> remembered = new ConcurrentHashMap<>();

  public TacoOfTheDayService(TacoCandidatePort candidates, TacoValidator validator,
      Clock clock, TacoRecommendationProperties properties) {
    this.candidates = candidates;
    this.validator = validator;
    this.clock = clock;
    this.properties = properties;
  }

  public Mono<TacoOfTheDay> today() {
    return recommendOn(LocalDate.now(clock.withZone(zone())));
  }

  /** Exposed so a test can ask for a date instead of moving the clock. */
  Mono<TacoOfTheDay> recommendOn(LocalDate date) {
    return Mono.defer(() -> {
      String cached = remembered.get(date);
      if (cached == null) {
        return choose(date);
      }
      return candidates.sellableCandidate(cached)
          .map(taco -> featured(taco, date))
          .switchIfEmpty(Mono.defer(() -> {
            remembered.remove(date);
            return choose(date);
          }));
    });
  }

  private Mono<TacoOfTheDay> choose(LocalDate date) {
    return pool()
        .flatMap(candidatesInOrder -> {
          if (candidatesInOrder.isEmpty()) {
            return Mono.error(new NoTacoOfTheDayException(date));
          }
          Taco pick = candidatesInOrder.get(indexOf(date, candidatesInOrder.size()));
          remember(date, pick.getId());
          return Mono.just(featured(pick, date));
        });
  }

  /**
   * Every taco that could be sold today, sorted by id. Validity is re-checked
   * with the same {@link TacoValidator} the order flow uses, so a leftover taco
   * left in the catalog by a customer is never advertised.
   */
  private Mono<List<Taco>> pool() {
    return candidates.sellableCandidates()
        .filter(taco -> taco.getId() != null)
        .filter(taco -> validator.violations(taco.getIngredients()).isEmpty())
        .collectSortedList(BY_ID);
  }

  private static int indexOf(LocalDate date, int poolSize) {
    return (int) Math.floorMod(date.toEpochDay(), poolSize);
  }

  private void remember(LocalDate date, String tacoId) {
    remembered.entrySet().removeIf(entry -> !entry.getKey().equals(date));
    remembered.put(date, tacoId);
  }

  private TacoOfTheDay featured(Taco taco, LocalDate date) {
    return new TacoOfTheDay(taco.getId(), taco.getName(), date,
        "Featured for " + date + ": \"" + taco.getName() + "\" is one of the tacos on sale"
            + " today. Everybody sees this same recommendation on " + date
            + ", and it is withdrawn as soon as the taco leaves the menu.");
  }

  private ZoneId zone() {
    return ZoneId.of(properties.getZone());
  }

  /** Visible for assertions: how many dates are currently remembered. */
  int rememberedDates() {
    return remembered.size();
  }

}
