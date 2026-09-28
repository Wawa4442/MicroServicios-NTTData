package tacos.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Taco;
import tacos.rules.RuleViolation;
import tacos.rules.TacoValidator;

/**
 * TC-20: one recommendation per business date, and the same one for everybody.
 *
 * <p>The tests never sleep and never wait for midnight. Time comes from an
 * injected {@link Clock}, so "what happens tomorrow" is a question the clock
 * answers instead of the test suite. That is not a testing convenience: the
 * production requirement is that the pick is a function of the business date, and
 * the only way to be sure of it is to be able to choose the date.
 */
public class TacoOfTheDayServiceTest {

  private static final ZoneId ZONE = ZoneId.of("America/Bogota");
  private static final LocalDate DAY = LocalDate.of(2026, 3, 14);

  private FakeCandidates catalog;
  private TacoOfTheDayService service;
  private TacoRecommendationProperties properties;

  @BeforeEach
  public void setup() {
    catalog = new FakeCandidates();
    catalog.replace(taco("taco-1"), taco("taco-2"), taco("taco-3"));
    properties = new TacoRecommendationProperties();
    properties.setZone(ZONE.getId());
    TacoValidator validator = mock(TacoValidator.class);
    when(validator.violations(anyList())).thenReturn(List.of());
    service = new TacoOfTheDayService(catalog, validator, clockAt(DAY), properties);
  }

  @Test
  public void tc20_theSameDateAlwaysGivesTheSameTaco() {
    TacoOfTheDay first = service.recommendOn(DAY).block();
    TacoOfTheDay second = service.recommendOn(DAY).block();

    assertEquals(first.getTacoId(), second.getTacoId());
    assertEquals(DAY, first.getDate(), "the answer says which day it is for");
  }

  @Test
  public void tc20_twoCustomersAskingAtTheSameTimeGetTheSameAnswer() {
    TacoOfTheDay forAlice = service.recommendOn(DAY).block();
    TacoOfTheDay forBob = new TacoOfTheDayService(catalog, alwaysValid(), clockAt(DAY), properties)
        .recommendOn(DAY).block();

    assertEquals(forAlice.getTacoId(), forBob.getTacoId(),
        "a recommendation that depended on who asked would be a lottery, not a "
            + "recommendation");
  }

  @Test
  public void tc20_thePickDoesNotDependOnTheOrderTheCatalogReturns() {
    catalog.replace(taco("taco-3"), taco("taco-1"), taco("taco-2"));
    TacoOfTheDay shuffled = service.recommendOn(DAY).block();

    catalog.replace(taco("taco-2"), taco("taco-3"), taco("taco-1"));
    TacoOfTheDay reordered = new TacoOfTheDayService(catalog, alwaysValid(), clockAt(DAY),
        properties).recommendOn(DAY).block();

    assertEquals(shuffled.getTacoId(), reordered.getTacoId(),
        "candidates are sorted by id before the index is computed, so the physical "
            + "order of the store cannot change the answer");
  }

  @Test
  public void tc20_consecutiveDaysAdvanceTheRotation() {
    String monday = service.recommendOn(DAY).block().getTacoId();
    String tuesday = new TacoOfTheDayService(catalog, alwaysValid(), clockAt(DAY.plusDays(1)),
        properties).recommendOn(DAY.plusDays(1)).block().getTacoId();
    String wednesday = new TacoOfTheDayService(catalog, alwaysValid(), clockAt(DAY.plusDays(2)),
        properties).recommendOn(DAY.plusDays(2)).block().getTacoId();

    assertNotEquals(monday, tuesday);
    assertNotEquals(tuesday, wednesday);
    assertNotEquals(monday, wednesday);
  }

  @Test
  public void tc20_theDateComesFromTheConfiguredZone() {
    // 2026-03-14T02:00Z is still the 13th in Bogotá, so a UTC clock would
    // answer with yesterday's taco.
    Clock lateNight = Clock.fixed(Instant.parse("2026-03-14T02:00:00Z"), ZoneId.of("UTC"));
    TacoOfTheDayService zoned = new TacoOfTheDayService(catalog, alwaysValid(), lateNight,
        properties);

    StepVerifier.create(zoned.today())
        .assertNext(answer -> assertEquals(DAY.minusDays(1), answer.getDate()))
        .verifyComplete();
  }

  @Test
  public void tc20_aTacoThatLeavesTheMenuIsWithdrawn() {
    String original = service.recommendOn(DAY).block().getTacoId();
    catalog.takeOffMenu(original);

    TacoOfTheDay afterWithdrawal = service.recommendOn(DAY).block();

    assertNotEquals(original, afterWithdrawal.getTacoId(),
        "the cache is re-checked against the catalog, so it cannot keep advertising a "
            + "taco the shop cannot sell");
  }

  @Test
  public void tc20_aTacoThatComesBackOnTheMenuCanBeRecommendedAgain() {
    service.recommendOn(DAY).block();
    catalog.takeOffMenu("taco-1");
    String replacement = service.recommendOn(DAY).block().getTacoId();
    assertNotEquals("taco-1", replacement, "while it is off the menu it cannot be featured");

    catalog.putBackOnMenu("taco-1");
    // The rotation is three days long, so three days after the withdrawal the
    // restored taco is at the same position it held before.
    String restored = service.recommendOn(DAY.plusDays(3)).block().getTacoId();

    assertEquals("taco-1", restored,
        "a taco that returns to the menu is eligible again, not permanently excluded");
  }

  @Test
  public void tc20_anInvalidTacoIsNeverAdvertised() {
    TacoValidator strict = mock(TacoValidator.class);
    when(strict.violations(anyList())).thenAnswer(call -> {
      @SuppressWarnings("unchecked")
      List<Ingredient> ingredients = call.getArgument(0);
      boolean isTheBrokenOne = ingredients.stream()
          .anyMatch(ingredient -> ingredient.getId().startsWith("taco-1"));
      return isTheBrokenOne
          ? List.of(RuleViolation.of("NO_BASE", "needs a base"))
          : List.of();
    });
    TacoOfTheDayService guarded =
        new TacoOfTheDayService(catalog, strict, clockAt(DAY), properties);

    List<String> picks = new ArrayList<>();
    picks.add(guarded.recommendOn(DAY).block().getTacoId());
    picks.add(guarded.recommendOn(DAY.plusDays(1)).block().getTacoId());
    picks.add(guarded.recommendOn(DAY.plusDays(2)).block().getTacoId());

    assertTrue(picks.stream().noneMatch("taco-1"::equals),
        "a taco the order flow would reject cannot be featured by the storefront: " + picks);
    assertEquals(List.of("taco-2", "taco-3", "taco-2"), picks,
        "the rotation runs over the sellable pool, which is now two tacos wide");
  }

  @Test
  public void tc20_anEmptyCatalogIsA404ShapedError() {
    catalog.replace();

    StepVerifier.create(service.recommendOn(DAY))
        .expectError(NoTacoOfTheDayException.class)
        .verify();
  }

  @Test
  public void tc20_theCatalogIsNotReloadedForEveryCustomer() {
    service.recommendOn(DAY).block();
    service.recommendOn(DAY).block();
    service.recommendOn(DAY).block();

    assertEquals(1, catalog.fullScans.get(),
        "the pick is cached per date, so the storefront does not re-read the whole "
            + "catalog on every visit");
  }

  @Test
  public void tc20_theCacheForgetsOtherDates() {
    for (int day = 0; day < 40; day++) {
      service.recommendOn(DAY.plusDays(day)).block();
    }

    assertEquals(1, service.rememberedDates(),
        "memory must not grow with uptime: one remembered date is enough");
  }

  @Test
  public void tc20_theRecommendationExplainsItself() {
    TacoOfTheDay answer = service.recommendOn(DAY).block();

    assertEquals("taco-1", answer.getTacoId());
    assertEquals("taco-1", answer.getName());
    assertTrue(answer.getReason().startsWith("Featured for " + DAY + ": \"taco-1\""),
        "the reason names the date and the taco the customer is looking at, but it does "
            + "not explain the ranking it cannot justify: " + answer.getReason());
  }

  private static TacoValidator alwaysValid() {
    TacoValidator validator = mock(TacoValidator.class);
    when(validator.violations(anyList())).thenReturn(List.of());
    return validator;
  }

  private static Clock clockAt(LocalDate date) {
    return Clock.fixed(date.atStartOfDay(ZONE).toInstant(), ZONE);
  }

  private static Taco taco(String id) {
    Taco taco = new Taco();
    taco.setId(id);
    taco.setName(id);
    taco.setIngredients(List.of(new Ingredient(id + "-base", "Tortilla", Ingredient.Type.WRAP)));
    return taco;
  }

  /**
   * A stand-in for the candidate port that behaves like a store: a set of tacos
   * that can be taken on and off the menu between calls.
   */
  private static final class FakeCandidates implements TacoCandidatePort {

    private final List<Taco> sellable = new ArrayList<>();
    private final AtomicInteger fullScans = new AtomicInteger();

    void replace(Taco... tacos) {
      sellable.clear();
      for (Taco taco : tacos) {
        sellable.add(taco);
      }
    }

    void takeOffMenu(String tacoId) {
      sellable.removeIf(taco -> taco.getId().equals(tacoId));
    }

    void putBackOnMenu(String tacoId) {
      sellable.add(taco(tacoId));
    }

    @Override
    public Flux<Taco> sellableCandidates() {
      fullScans.incrementAndGet();
      return Flux.fromIterable(sellable);
    }

    @Override
    public Mono<Taco> sellableCandidate(String tacoId) {
      return Flux.fromIterable(sellable)
          .filter(taco -> taco.getId().equals(tacoId))
          .next();
    }
  }

}
