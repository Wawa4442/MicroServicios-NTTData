package tacos.favorites;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Favorite;
import tacos.Taco;
import tacos.api.dto.FavoriteResponse;
import tacos.data.FavoriteRepository;
import tacos.data.TacoRepository;
import tacos.web.api.TacoNotFoundException;

/**
 * TC-21: favorites are the customer's own, idempotent to add, idempotent to
 * remove, and honest about a taco that left the menu.
 *
 * <p>The repository is a mock on purpose. What these cases pin is which calls
 * the service does and with which identity: an insert that only ever carries the
 * authenticated user, a name lookup resolved with one query instead of one per
 * row, and a duplicate answered as a success. A mock is the only way to see that.
 */
public class FavoriteServiceTest {

  private static final String ALICE = "userA";
  private static final String BOB = "userB";
  private static final String TACO = "taco-1";

  private FavoriteRepository favorites;
  private TacoRepository tacos;
  private FavoriteService service;

  @BeforeEach
  public void setup() {
    favorites = mock(FavoriteRepository.class);
    tacos = mock(TacoRepository.class);
    service = new FavoriteService(favorites, tacos);
  }

  @Test
  public void tc21_savingAFavoriteStoresTheGivenUserAndTaco() {
    when(tacos.findById(TACO)).thenReturn(Mono.just(taco(TACO, "Al Pastor")));
    when(favorites.findByUserIdAndTacoId(ALICE, TACO)).thenReturn(Mono.empty());
    when(favorites.save(any(Favorite.class)))
        .thenAnswer(call -> Mono.just(call.getArgument(0)));

    StepVerifier.create(service.add(ALICE, TACO))
        .assertNext(saved -> {
          assertEquals(TACO, saved.getTacoId());
          assertEquals("Al Pastor", saved.getTacoName());
          assertTrue(!saved.isOrphaned());
        })
        .verifyComplete();

    ArgumentCaptor<Favorite> captor = ArgumentCaptor.forClass(Favorite.class);
    verify(favorites).save(captor.capture());
    assertEquals(ALICE, captor.getValue().getUserId());
    assertEquals(TACO, captor.getValue().getTacoId());
  }

  @Test
  public void tc21_savingTwiceKeepsOneRowAndDoesNotInsertAgain() {
    when(tacos.findById(TACO)).thenReturn(Mono.just(taco(TACO, "Al Pastor")));
    when(favorites.findByUserIdAndTacoId(ALICE, TACO))
        .thenReturn(Mono.just(favorite("f-1", ALICE, TACO, 1L)));

    StepVerifier.create(service.add(ALICE, TACO))
        .assertNext(saved -> assertEquals(TACO, saved.getTacoId()))
        .verifyComplete();

    verify(favorites, never()).save(any(Favorite.class));
  }

  @Test
  public void tc21_aConcurrentInsertIsAnsweredWithTheWinnersRow() {
    // The unique (userId, tacoId) index is what makes the duplicate possible:
    // the first writer's row is the favorite, so the loser gets it back.
    when(tacos.findById(TACO)).thenReturn(Mono.just(taco(TACO, "Al Pastor")));
    when(favorites.findByUserIdAndTacoId(ALICE, TACO))
        .thenReturn(Mono.empty(), Mono.just(favorite("f-1", ALICE, TACO, 1L)));
    when(favorites.save(any(Favorite.class)))
        .thenReturn(Mono.error(new DuplicateKeyException("favorite_user_taco_unique")));

    StepVerifier.create(service.add(ALICE, TACO))
        .assertNext(saved -> assertEquals(TACO, saved.getTacoId()))
        .verifyComplete();
  }

  @Test
  public void tc21_aTacoThatDoesNotExistIsNotFavorited() {
    when(tacos.findById(TACO)).thenReturn(Mono.empty());

    StepVerifier.create(service.add(ALICE, TACO))
        .expectError(TacoNotFoundException.class)
        .verify();

    verify(favorites, never()).save(any(Favorite.class));
  }

  @Test
  public void tc21_theListIsScopedToTheAuthenticatedUser() {
    when(favorites.countByUserId(ALICE)).thenReturn(Mono.just(1L));
    when(favorites.findByUserIdOrderBySavedAtDescIdAsc(eq(ALICE), any()))
        .thenReturn(Flux.just(favorite("f-1", ALICE, TACO, 1L)));
    when(tacos.findAllById(List.of(TACO))).thenReturn(Flux.just(taco(TACO, "Al Pastor")));

    StepVerifier.create(service.list(ALICE, 0, 20))
        .assertNext(page -> {
          assertEquals(1, page.getContent().size());
          assertEquals(1L, page.getTotalElements());
          assertEquals(0, page.getPage());
          assertEquals("Al Pastor", page.getContent().get(0).getTacoName());
        })
        .verifyComplete();

    verify(favorites, never()).countByUserId(BOB);
    verify(favorites, never()).findByUserIdOrderBySavedAtDescIdAsc(eq(BOB), any());
  }

  @Test
  public void tc21_aDeletedTacoIsReportedAsOrphanedRatherThanDropped() {
    when(favorites.countByUserId(ALICE)).thenReturn(Mono.just(1L));
    when(favorites.findByUserIdOrderBySavedAtDescIdAsc(eq(ALICE), any()))
        .thenReturn(Flux.just(favorite("f-1", ALICE, TACO, 1L)));
    when(tacos.findAllById(List.of(TACO))).thenReturn(Flux.empty());

    StepVerifier.create(service.list(ALICE, 0, 20))
        .assertNext(page -> {
          FavoriteResponse orphan = page.getContent().get(0);
          assertTrue(orphan.isOrphaned());
          assertNull(orphan.getTacoName());
        })
        .verifyComplete();
  }

  @Test
  public void tc21_namesAreResolvedWithASingleQueryForTheWholePage() {
    when(favorites.countByUserId(ALICE)).thenReturn(Mono.just(2L));
    when(favorites.findByUserIdOrderBySavedAtDescIdAsc(eq(ALICE), any()))
        .thenReturn(Flux.just(favorite("f-1", ALICE, "taco-1", 1L),
            favorite("f-2", ALICE, "taco-2", 2L)));
    when(tacos.findAllById(anyIterable())).thenReturn(Flux.just(taco("taco-1", "Al Pastor"),
        taco("taco-2", "Barbacoa")));

    StepVerifier.create(service.list(ALICE, 0, 20))
        .assertNext(page -> assertEquals(2, page.getContent().size()))
        .verifyComplete();

    verify(tacos, times(1)).findAllById(List.of("taco-1", "taco-2"));
  }

  @Test
  public void tc21_anEmptyPageDoesNotQueryTheCatalogAtAll() {
    when(favorites.countByUserId(ALICE)).thenReturn(Mono.just(0L));
    when(favorites.findByUserIdOrderBySavedAtDescIdAsc(eq(ALICE), any()))
        .thenReturn(Flux.empty());

    StepVerifier.create(service.list(ALICE, 0, 20))
        .assertNext(page -> {
          assertEquals(0, page.getContent().size());
          assertEquals(0L, page.getTotalElements());
        })
        .verifyComplete();

    verify(tacos, never()).findAllById(anyIterable());
  }

  @Test
  public void tc21_removingIsScopedToTheOwnerAndSucceedsTwice() {
    when(favorites.deleteByUserIdAndTacoId(ALICE, TACO)).thenReturn(Mono.empty());

    StepVerifier.create(service.remove(ALICE, TACO)).verifyComplete();
    StepVerifier.create(service.remove(ALICE, TACO)).verifyComplete();

    verify(favorites, times(2)).deleteByUserIdAndTacoId(ALICE, TACO);
    verify(favorites, never()).deleteByUserIdAndTacoId(BOB, TACO);
  }

  @Test
  public void tc21_thePageCarriesTheMetadataTheUiNeeds() {
    when(favorites.countByUserId(ALICE)).thenReturn(Mono.just(5L));
    when(favorites.findByUserIdOrderBySavedAtDescIdAsc(eq(ALICE), any()))
        .thenReturn(Flux.just(favorite("f-1", ALICE, TACO, 1L)));
    when(tacos.findAllById(List.of(TACO))).thenReturn(Flux.just(taco(TACO, "Al Pastor")));

    StepVerifier.create(service.list(ALICE, 0, 2))
        .assertNext(page -> {
          assertEquals(0, page.getPage());
          assertEquals(2, page.getSize());
          assertEquals(5L, page.getTotalElements());
          assertEquals(3, page.getTotalPages());
          assertTrue(page.isHasNext());
        })
        .verifyComplete();
  }

  @Test
  public void tc21_aRenamedTacoShowsItsCurrentName() {
    when(favorites.countByUserId(ALICE)).thenReturn(Mono.just(1L));
    when(favorites.findByUserIdOrderBySavedAtDescIdAsc(eq(ALICE), any()))
        .thenReturn(Flux.just(favorite("f-1", ALICE, TACO, 1L)));
    when(tacos.findAllById(List.of(TACO))).thenReturn(Flux.just(taco(TACO, "Al Pastor Picante")));

    StepVerifier.create(service.list(ALICE, 0, 20))
        .assertNext(page -> assertEquals("Al Pastor Picante",
            page.getContent().get(0).getTacoName()))
        .verifyComplete();
  }

  private static Taco taco(String id, String name) {
    Taco taco = new Taco();
    taco.setId(id);
    taco.setName(name);
    return taco;
  }

  private static Favorite favorite(String id, String userId, String tacoId, long savedAt) {
    Favorite favorite = new Favorite(userId, tacoId);
    favorite.setId(id);
    favorite.setSavedAt(new Date(savedAt));
    return favorite;
  }

}
