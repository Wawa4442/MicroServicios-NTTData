package tacos.announcements;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.web.api.CallerIdentity;
import tacos.web.api.CallerIdentityResolver;
import tacos.web.api.RestProblemHandler;

/**
 * TC-33 through HTTP: stable ids, no positional deletes.
 */
public class AdminAnnouncementControllerTest {

  private OpsAnnouncementRepository repository;
  private WebTestClient client;

  @BeforeEach
  public void setup() {
    AnnouncementProperties props = new AnnouncementProperties();
    java.time.Clock clock = java.time.Clock.systemUTC();
    repository = Mockito.mock(OpsAnnouncementRepository.class);
    AnnouncementService service = new AnnouncementService(repository, props, clock);
    CallerIdentityResolver identities = Mockito.mock(CallerIdentityResolver.class);
    when(identities.identity()).thenReturn(Mono.just(CallerIdentity.admin("admin1")));
    AdminAnnouncementController controller =
        new AdminAnnouncementController(service, identities);
    client = WebTestClient.bindToController(controller)
        .controllerAdvice(new RestProblemHandler())
        .build();
    when(repository.save(any(OpsAnnouncement.class)))
        .thenAnswer(inv -> {
          OpsAnnouncement row = inv.getArgument(0);
          row.setId("stable-id-1");
          return Mono.just(row);
        });
    when(repository.findByActiveTrueOrderByCreatedAtDesc()).thenReturn(Flux.empty());
  }

  @Test
  public void tc33_create_returnsStableId() {
    client.post().uri("/api/admin/announcements")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"text\":\"Board cleaned\",\"severity\":\"INFO\"}")
        .exchange()
        .expectStatus().isCreated()
        .expectBody()
        .jsonPath("$.id").isEqualTo("stable-id-1")
        .jsonPath("$.text").isEqualTo("Board cleaned");
  }

  @Test
  public void tc33_createWithBlankText_isRejected() {
    client.post().uri("/api/admin/announcements")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"text\":\"  \"}")
        .exchange()
        .expectStatus().isBadRequest();
  }

  @Test
  public void tc33_deleteUnknownId_isNotFound() {
    when(repository.findById(anyString())).thenReturn(Mono.empty());
    client.delete().uri("/api/admin/announcements/nope")
        .exchange()
        .expectStatus().isNotFound();
  }
}
