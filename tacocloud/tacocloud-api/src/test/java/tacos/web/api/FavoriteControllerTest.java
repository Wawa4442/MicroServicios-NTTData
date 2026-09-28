package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Favorite;
import tacos.Taco;
import tacos.User;
import tacos.data.FavoriteRepository;
import tacos.data.TacoRepository;
import tacos.favorites.FavoriteProperties;
import tacos.favorites.FavoriteService;

/**
 * TC-21: favorites belong to one customer and only to that customer.
 *
 * <p>The identity comes from the real security context instead of a stubbed id.
 * That is deliberate: a stub would let these tests pass while the controller read
 * the wrong thing from the context, which is exactly the bug the challenge is
 * about.
 */
public class FavoriteControllerTest {

  private static final User ALICE = user("userA", "alice");
  private static final User BOB = user("userB", "bob");

  private FavoriteRepository favorites;
  private TacoRepository tacos;
  private FavoriteService service;
  private WebTestClient anonymous;
  private WebTestClient asAlice;
  private WebTestClient asBob;

  @BeforeEach
  public void setup() {
    favorites = mock(FavoriteRepository.class);
    tacos = mock(TacoRepository.class);
    when(tacos.findById("taco-1")).thenReturn(Mono.just(taco("taco-1")));
    when(tacos.findById("ghost")).thenReturn(Mono.empty());
    when(favorites.save(any(Favorite.class)))
        .thenAnswer(call -> Mono.just(assigned(call.getArgument(0), "fav-1")));
    when(favorites.deleteByUserIdAndTacoId(anyString(), anyString()))
        .thenReturn(Mono.empty());
    when(favorites.findByUserIdOrderBySavedAtDescIdAsc(anyString(), any()))
        .thenReturn(Flux.empty());
    when(favorites.countByUserId(anyString())).thenReturn(Mono.just(0L));

    service = new FavoriteService(favorites, tacos);
    FavoriteController controller = new FavoriteController(service,
        new CallerIdentityResolver(), new FavoriteProperties());
    anonymous = WebTestClient.bindToController(controller)
        .controllerAdvice(new RestProblemHandler())
        .build();
    asAlice = clientFor(controller, ALICE);
    asBob = clientFor(controller, BOB);
  }

  @Test
  public void tc21_savingTwiceKeepsOneRow() {
    when(favorites.findByUserIdAndTacoId("userA", "taco-1"))
        .thenReturn(Mono.empty())
        .thenReturn(Mono.just(assigned(new Favorite("userA", "taco-1"), "fav-1")));

    asAlice.put().uri("/api/users/me/favorites/taco-1")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.tacoId").isEqualTo("taco-1")
        .jsonPath("$.tacoName").isEqualTo("Taco 1")
        .jsonPath("$.orphaned").isEqualTo(false);

    asAlice.put().uri("/api/users/me/favorites/taco-1")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.tacoId").isEqualTo("taco-1");

    verify(favorites, times(1)).save(any(Favorite.class));
  }

  @Test
  public void tc21_theSavedRowCarriesTheCallerNotTheBody() {
    when(favorites.findByUserIdAndTacoId("userA", "taco-1")).thenReturn(Mono.empty());

    asAlice.put().uri("/api/users/me/favorites/taco-1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"userId\":\"userB\",\"tacoId\":\"taco-9\"}")
        .exchange()
        .expectStatus().isOk();

    ArgumentCaptor<Favorite> saved = ArgumentCaptor.forClass(Favorite.class);
    verify(favorites).save(saved.capture());
    assertEquals("userA", saved.getValue().getUserId(),
        "the identity comes from the session, so a body claiming to be somebody else "
            + "changes nothing");
    assertEquals("taco-1", saved.getValue().getTacoId(),
        "the taco comes from the path, not from the body");
  }

  @Test
  public void tc21_aFavoriteOfADeletedTacoIsMarkedNotHidden() {
    when(favorites.findByUserIdOrderBySavedAtDescIdAsc(anyString(), any()))
        .thenReturn(Flux.just(assigned(new Favorite("userA", "taco-1"), "fav-1")));
    when(favorites.countByUserId("userA")).thenReturn(Mono.just(1L));
    when(tacos.findAllById(ids())).thenReturn(Flux.empty());

    asAlice.get().uri("/api/users/me/favorites")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.content[0].tacoId").isEqualTo("taco-1")
        .jsonPath("$.content[0].orphaned").isEqualTo(true)
        .jsonPath("$.content[0].tacoName").doesNotExist()
        .jsonPath("$.totalElements").isEqualTo(1);
  }

  @Test
  public void tc21_theListingOnlyAsksForTheCallersOwnRows() {
    asBob.get().uri("/api/users/me/favorites").exchange().expectStatus().isOk();
    asAlice.get().uri("/api/users/me/favorites").exchange().expectStatus().isOk();

    verify(favorites).countByUserId("userB");
    verify(favorites).countByUserId("userA");
    verify(favorites, never()).findAllById(ids());
  }

  @Test
  public void tc21_theListingResolvesNamesInOneQuery() {
    when(favorites.findByUserIdOrderBySavedAtDescIdAsc(anyString(), any()))
        .thenReturn(Flux.just(
            assigned(new Favorite("userA", "taco-1"), "fav-1"),
            assigned(new Favorite("userA", "taco-2"), "fav-2")));
    when(favorites.countByUserId("userA")).thenReturn(Mono.just(2L));
    when(tacos.findAllById(ids())).thenReturn(Flux.just(taco("taco-1"), taco("taco-2")));

    asAlice.get().uri("/api/users/me/favorites")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.content[0].tacoName").isEqualTo("Taco 1")
        .jsonPath("$.content[1].tacoName").isEqualTo("Taco 2");

    verify(tacos, times(1)).findAllById(ids());
    verify(tacos, never()).findById("taco-1");
  }

  @Test
  public void tc21_removingIsIdempotentAndScopedToTheCaller() {
    asBob.delete().uri("/api/users/me/favorites/taco-1").exchange().expectStatus().isNoContent();
    asBob.delete().uri("/api/users/me/favorites/taco-1").exchange().expectStatus().isNoContent();

    verify(favorites, times(2)).deleteByUserIdAndTacoId("userB", "taco-1");
    verify(favorites, never()).deleteByUserIdAndTacoId("userA", "taco-1");
  }

  @Test
  public void tc21_savingATacoThatDoesNotExistIsA404() {
    asAlice.put().uri("/api/users/me/favorites/ghost").exchange().expectStatus().isNotFound();

    verify(favorites, never()).save(any(Favorite.class));
  }

  @Test
  public void tc21_anAnonymousCallerIsA401() {
    anonymous.get().uri("/api/users/me/favorites").exchange().expectStatus().isUnauthorized();
    anonymous.put().uri("/api/users/me/favorites/taco-1")
        .exchange().expectStatus().isUnauthorized();
    anonymous.delete().uri("/api/users/me/favorites/taco-1")
        .exchange().expectStatus().isUnauthorized();
  }

  @Test
  public void tc21_theResponseCarriesNoUserInformation() {
    when(favorites.findByUserIdOrderBySavedAtDescIdAsc(anyString(), any()))
        .thenReturn(Flux.just(assigned(new Favorite("userA", "taco-1"), "fav-1")));
    when(tacos.findAllById(ids())).thenReturn(Flux.just(taco("taco-1")));

    asAlice.get().uri("/api/users/me/favorites")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.content[0].userId").doesNotExist()
        .jsonPath("$.content[0].user").doesNotExist()
        .jsonPath("$.content[0].password").doesNotExist();
  }

  @Test
  public void tc21_aConcurrentDoubleSaveIsASuccessNotAConflict() {
    when(favorites.findByUserIdAndTacoId("userA", "taco-1"))
        .thenReturn(Mono.empty())
        .thenReturn(Mono.just(assigned(new Favorite("userA", "taco-1"), "fav-1")));
    when(favorites.save(any(Favorite.class)))
        .thenReturn(Mono.error(new DuplicateKeyException("favorite_user_taco_unique")));

    StepVerifier.create(service.add("userA", "taco-1"))
        .assertNext(response -> assertEquals("taco-1", response.getTacoId()))
        .verifyComplete();
  }

  @Test
  public void tc21_pageWindowIsBounded() {
    asAlice.get().uri("/api/users/me/favorites?size=0")
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.code").isEqualTo("invalid_page");
  }

  @Test
  public void tc21_theListingUsesTheConfiguredWindow() {
    asAlice.get().uri("/api/users/me/favorites").exchange().expectStatus().isOk();

    ArgumentCaptor<Pageable> window = ArgumentCaptor.forClass(Pageable.class);
    verify(favorites).findByUserIdOrderBySavedAtDescIdAsc(eq("userA"), window.capture());
    assertEquals(20, window.getValue().getPageSize());
    assertEquals(0, window.getValue().getPageNumber());
  }

  /** Narrows the overloaded findAllById to the one the service actually calls. */
  private static Iterable<String> ids() {
    return org.mockito.ArgumentMatchers.<Iterable<String>>any();
  }

  private static WebTestClient clientFor(FavoriteController controller, User user) {
    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
        user, user.getPassword(), user.getAuthorities());
    return WebTestClient.bindToController(controller)
        .controllerAdvice(new RestProblemHandler())
        .webFilter((exchange, chain) -> chain.filter(exchange)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
        .build();
  }

  private static Favorite assigned(Favorite favorite, String id) {
    favorite.setId(id);
    return favorite;
  }

  private static Taco taco(String id) {
    Taco taco = new Taco();
    taco.setId(id);
    taco.setName("Taco " + id.substring(id.length() - 1));
    return taco;
  }

  private static User user(String id, String username) {
    User user = new User(username, "pw", username, "1 Oak", "Austin", "TX", "78701", "555",
        username + "@taco.cl");
    user.setId(id);
    user.setRole("ROLE_USER");
    return user;
  }

}
