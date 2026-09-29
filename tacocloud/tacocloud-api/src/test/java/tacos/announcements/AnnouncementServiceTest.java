package tacos.announcements;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * TC-33 at the service level: persistence shape, bounds and expiry.
 */
public class AnnouncementServiceTest {

  private OpsAnnouncementRepository repository;
  private AnnouncementProperties props;
  private Clock clock;
  private AnnouncementService service;

  @BeforeEach
  public void setup() {
    repository = Mockito.mock(OpsAnnouncementRepository.class);
    props = new AnnouncementProperties();
    props.setMaxActive(20);
    props.setMaxLength(500);
    props.setDefaultTtl(Duration.ofDays(7));
    clock = Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneId.of("UTC"));
    service = new AnnouncementService(repository, props, clock);
    when(repository.save(any(OpsAnnouncement.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
  }

  @Test
  public void tc33_create_persistsWithStableIdShape() {
    when(repository.findByActiveTrueOrderByCreatedAtDesc()).thenReturn(Flux.empty());

    StepVerifier.create(service.create("Kitchen closes early", AnnouncementSeverity.WARN,
            null, "admin1"))
        .expectNextMatches(saved ->
            "Kitchen closes early".equals(saved.getText())
            && saved.getSeverity() == AnnouncementSeverity.WARN
            && saved.getCreatedAt().equals(clock.instant())
            && saved.getExpiresAt().isAfter(clock.instant())
            && "admin1".equals(saved.getCreatedBy())
            && saved.isActive())
        .verifyComplete();
  }

  @Test
  public void tc33_emptyOrControlText_isRejected() {
    when(repository.findByActiveTrueOrderByCreatedAtDesc()).thenReturn(Flux.empty());

    StepVerifier.create(service.create("  ", null, null, "admin1"))
        .expectError(AnnouncementValidationException.class)
        .verify();

    StepVerifier.create(service.create("hello\u0001world", null, null, "admin1"))
        .expectError(AnnouncementValidationException.class)
        .verify();
  }

  @Test
  public void tc33_tooManyActive_isRejected() {
    OpsAnnouncement existing = new OpsAnnouncement();
    when(repository.findByActiveTrueOrderByCreatedAtDesc())
        .thenReturn(Flux.range(0, 20).map(i -> existing));

    StepVerifier.create(service.create("one more", null, null, "admin1"))
        .expectError(AnnouncementValidationException.class)
        .verify();
  }

  @Test
  public void tc33_expired_areHidden() {
    OpsAnnouncement fresh = new OpsAnnouncement();
    fresh.setText("fresh");
    fresh.setExpiresAt(clock.instant().plus(Duration.ofHours(1)));
    OpsAnnouncement old = new OpsAnnouncement();
    old.setText("old");
    old.setExpiresAt(clock.instant().minus(Duration.ofHours(1)));
    when(repository.findByActiveTrueOrderByCreatedAtDesc())
        .thenReturn(Flux.just(fresh, old));

    StepVerifier.create(service.listActive())
        .expectNextMatches(a -> "fresh".equals(a.getText()))
        .verifyComplete();
  }

  @Test
  public void tc33_deleteById_removesTheRightOne() {
    OpsAnnouncement row = new OpsAnnouncement();
    row.setId("abc123");
    when(repository.findById("abc123")).thenReturn(Mono.just(row));
    when(repository.findById("missing")).thenReturn(Mono.empty());
    when(repository.delete(row)).thenReturn(Mono.empty());

    StepVerifier.create(service.deleteById("abc123")).verifyComplete();

    StepVerifier.create(service.deleteById("missing"))
        .expectError(AnnouncementNotFoundException.class)
        .verify();
  }
}
