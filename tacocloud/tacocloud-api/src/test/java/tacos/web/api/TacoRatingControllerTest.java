package tacos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.TacoRating;
import tacos.User;
import tacos.api.dto.RatingResponse;
import tacos.api.dto.TopTacoResponse;
import tacos.rating.InvalidRatingException;
import tacos.rating.TacoRatingService;

/**
 * TC-22 at the HTTP edge.
 *
 * <p>The service is mocked, so what is under test is the part the service
 * cannot police: that identity comes from the session and never from the body,
 * that a score outside the scale is refused before any work happens, and that
 * the chart is reachable without signing in.
 */
public class TacoRatingControllerTest {

  private static final String TACO_ID = "taco-1";

  private TacoRatingService ratings;
  private WebTestClient signedIn;
  private WebTestClient anonymous;

  @BeforeEach
  public void setup() {
    ratings = mock(TacoRatingService.class);
    TacoRatingController controller =
        new TacoRatingController(ratings, new CallerIdentityResolver());

    Taco taco = new Taco();
    taco.setId(TACO_ID);
    taco.setName("Taco al pastor");
    when(ratings.rate(any(), any(), any()))
        .thenReturn(Mono.just(RatingResponse.of(rated(TACO_ID, 4),
            new BigDecimal("4.00"), 1)));
    when(ratings.top(any()))
        .thenReturn(Mono.just(List.of(
            TopTacoResponse.of("taco-2", named("taco-2", "Taco de barbacoa"),
                new BigDecimal("4.75"), 40))));

    signedIn = clientFor(controller, "userA");
    anonymous = WebTestClient.bindToController(controller)
        .controllerAdvice(new RestProblemHandler())
        .build();
  }

  private static WebTestClient clientFor(TacoRatingController controller, String username) {
    User user = new User(username, "pw", username, "1 Oak", "Austin", "TX", "78701", "555",
        username + "@taco.cl");
    user.setId(username);
    user.setRole("ROLE_USER");
    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
        user, user.getPassword(), user.getAuthorities());
    return WebTestClient.bindToController(controller)
        .controllerAdvice(new RestProblemHandler())
        .webFilter((exchange, chain) -> chain.filter(exchange)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
        .build();
  }

  @Test
  public void tc22_aCustomerRatesAsThemselves() {
    signedIn.put().uri("/api/tacos/{id}/rating", TACO_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .syncBody(rating(4))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.tacoId").isEqualTo(TACO_ID)
        .jsonPath("$.score").isEqualTo(4)
        .jsonPath("$.averageScore").isEqualTo(4.0)
        .jsonPath("$.votes").isEqualTo(1);

    verify(ratings).rate(eq("userA"), eq(TACO_ID), eq(4));
  }

  @Test
  public void tc22_theBodyCannotSayWhoIsRating() {
    signedIn.put().uri("/api/tacos/{id}/rating", TACO_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .syncBody("{\"score\":5,\"userId\":\"userB\"}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.tacoId").isEqualTo(TACO_ID);

    verify(ratings).rate(eq("userA"), eq(TACO_ID), eq(5));
  }

  @Test
  public void tc22_anUnknownExtraFieldIsNotAnError() {
    signedIn.put().uri("/api/tacos/{id}/rating", TACO_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .syncBody("{\"score\":2,\"tacoId\":\"taco-9\"}")
        .exchange()
        .expectStatus().isOk();

    verify(ratings).rate(eq("userA"), eq(TACO_ID), eq(2));
  }

  @Test
  public void tc22_ratingRequiresASignIn() {
    anonymous.put().uri("/api/tacos/{id}/rating", TACO_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .syncBody(rating(4))
        .exchange()
        .expectStatus().isUnauthorized();

    verifyNoInteractions(ratings);
  }

  @Test
  public void tc22_aScoreAboveTheScaleIsA400() {
    signedIn.put().uri("/api/tacos/{id}/rating", TACO_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .syncBody("{\"score\":9}")
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.code").isEqualTo("validation_error")
        .jsonPath("$.violations[0].field").isEqualTo("score");

    verifyNoInteractions(ratings);
  }

  @Test
  public void tc22_aMissingScoreIsA400() {
    signedIn.put().uri("/api/tacos/{id}/rating", TACO_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .syncBody("{}")
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.code").isEqualTo("validation_error");

    verifyNoInteractions(ratings);
  }

  @Test
  public void tc22_aScaleWiderThanTheBodyAnnotationsIsCheckedByTheService() {
    when(ratings.rate(any(), any(), any()))
        .thenReturn(Mono.error(new InvalidRatingException("score must be between 1 and 10.")));

    signedIn.put().uri("/api/tacos/{id}/rating", TACO_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .syncBody(rating(5))
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.BAD_REQUEST)
        .expectBody()
        .jsonPath("$.code").isEqualTo("invalid_rating");
  }

  @Test
  public void tc22_ratingATacoThatIsNotOnTheMenuIsA404() {
    when(ratings.rate(any(), any(), any()))
        .thenReturn(Mono.error(new TacoNotFoundException(TACO_ID)));

    signedIn.put().uri("/api/tacos/{id}/rating", "ghost")
        .contentType(MediaType.APPLICATION_JSON)
        .syncBody(rating(4))
        .exchange()
        .expectStatus().isNotFound()
        .expectBody()
        .jsonPath("$.code").isEqualTo("taco_not_found");
  }

  @Test
  public void tc22_theChartDoesNotConsultTheSession() {
    // The chart belongs to the catalog, so the handler takes no identity from
    // the caller. The security configuration is a separate matter: GET
    // /api/tacos/** is still a signed-in surface, which is asserted in
    // SecurityAuthorizationTest.
    anonymous.get().uri("/api/tacos/top")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$[0].tacoId").isEqualTo("taco-2")
        .jsonPath("$[0].name").isEqualTo("Taco de barbacoa")
        .jsonPath("$[0].averageScore").isEqualTo(4.75)
        .jsonPath("$[0].votes").isEqualTo(40);

    verify(ratings).top(null);
  }

  @Test
  public void tc22_theChartLimitIsOptional() {
    anonymous.get().uri("/api/tacos/top?limit=3")
        .exchange()
        .expectStatus().isOk();

    verify(ratings).top(3);
  }

  @Test
  public void tc22_anUnusableChartLimitIsA422() {
    when(ratings.top(any()))
        .thenReturn(Mono.error(new InvalidRatingException("limit must be at least 1.")));

    anonymous.get().uri("/api/tacos/top?limit=0")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.BAD_REQUEST)
        .expectBody()
        .jsonPath("$.code").isEqualTo("invalid_rating");
  }

  @Test
  public void tc22_theChartIgnoresPagingParameters() {
    // The chart is a fixed-size leaderboard, not a collection; a client that
    // sends paging parameters gets the leaderboard, not a page of it.
    anonymous.get().uri("/api/tacos/top?page=0&size=5")
        .exchange()
        .expectStatus().isOk();

    verify(ratings, times(1)).top(any());
    verify(ratings, never()).rate(any(), any(), any());
  }

  @Test
  public void tc22_anEmptyChartIsAnEmptyArray() {
    when(ratings.top(any())).thenReturn(Mono.just(List.of()));

    anonymous.get().uri("/api/tacos/top")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .json("[]");
  }

  @Test
  public void tc22_noVoteOfAnotherCustomerIsEverExposed() {
    signedIn.put().uri("/api/tacos/{id}/rating", TACO_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .syncBody(rating(4))
        .exchange()
        .expectStatus().isOk()
        .expectBody(String.class)
        .value(body -> {
          org.junit.jupiter.api.Assertions.assertFalse(body.contains("userA"),
              "the response must not echo the caller, and certainly not another one");
          org.junit.jupiter.api.Assertions.assertFalse(body.contains("password"));
        });
  }

  @Test
  public void tc22_aBodyThatIsNotJsonIsA415() {
    signedIn.put().uri("/api/tacos/{id}/rating", TACO_ID)
        .contentType(MediaType.TEXT_PLAIN)
        .syncBody("4")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);

    verifyNoInteractions(ratings);
  }

  @Test
  public void tc22_ratingIsAputSoRepeatingItIsSafe() {
    for (int attempt = 0; attempt < 3; attempt++) {
      signedIn.put().uri("/api/tacos/{id}/rating", TACO_ID)
          .contentType(MediaType.APPLICATION_JSON)
          .syncBody(rating(4))
          .exchange()
          .expectStatus().isOk();
    }

    verify(ratings, times(3)).rate(eq("userA"), eq(TACO_ID), eq(4));
  }

  private static String rating(int score) {
    return "{\"score\":" + score + "}";
  }

  private static TacoRating rated(String tacoId, int score) {
    TacoRating rating = new TacoRating("userA", tacoId, score);
    rating.setId("rating-1");
    return rating;
  }

  private static Taco named(String id, String name) {
    Taco taco = new Taco();
    taco.setId(id);
    taco.setName(name);
    return taco;
  }

}
