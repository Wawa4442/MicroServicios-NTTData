package tacos.announcements;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Keeps the announcement board honest (TC-33).
 *
 * <p>Creation validates the text (not blank, bounded, no control chars),
 * stamps who/when with the caller's audit label, and refuses to grow past
 * {@code max-active}. Listing only shows active, unexpired rows, newest
 * first. Deletion is by stable id, so removing one never shifts the others.
 * Everything is a composed publisher: no {@code block()}, no
 * {@code subscribe()}, no shared {@code ArrayList}.
 */
@Service
public class AnnouncementService {

  private final OpsAnnouncementRepository repository;
  private final AnnouncementProperties props;
  private final Clock clock;

  public AnnouncementService(OpsAnnouncementRepository repository,
                             AnnouncementProperties props, Clock clock) {
    this.repository = repository;
    this.props = props;
    this.clock = clock;
  }

  public Mono<OpsAnnouncement> create(String text, AnnouncementSeverity severity,
                                      Instant expiresAt, String createdBy) {
    return Mono.defer(() -> {
      String clean = text == null ? "" : text.trim();
      if (clean.isEmpty()) {
        return Mono.error(new AnnouncementValidationException("text must not be empty."));
      }
      if (clean.length() > props.getMaxLength()) {
        return Mono.error(new AnnouncementValidationException(
            "text must be at most " + props.getMaxLength() + " characters."));
      }
      if (hasControlChars(clean)) {
        return Mono.error(new AnnouncementValidationException(
            "text must not contain control characters."));
      }
      Instant now = clock.instant();
      Instant until = expiresAt;
      if (until == null) {
        until = now.plus(props.getDefaultTtl());
      }
      if (!until.isAfter(now)) {
        return Mono.error(new AnnouncementValidationException(
            "expiresAt must be in the future."));
      }
      OpsAnnouncement row = new OpsAnnouncement();
      row.setText(clean);
      row.setSeverity(severity == null ? AnnouncementSeverity.INFO : severity);
      row.setCreatedAt(now);
      row.setExpiresAt(until);
      row.setActiveUntil(until);
      row.setCreatedBy(createdBy == null ? "operator" : createdBy);
      row.setActive(true);
      return repository.findByActiveTrueOrderByCreatedAtDesc()
          .count()
          .flatMap(active -> {
            if (active >= props.getMaxActive()) {
              return Mono.error(new AnnouncementValidationException(
                  "too many active announcements (max " + props.getMaxActive() + ")."));
            }
            return repository.save(row);
          });
    });
  }

  public Flux<OpsAnnouncement> listActive() {
    Instant now = clock.instant();
    return repository.findByActiveTrueOrderByCreatedAtDesc()
        .filter(row -> row.getExpiresAt() == null || row.getExpiresAt().isAfter(now));
  }

  public Mono<Void> deleteById(String id) {
    return repository.findById(id)
        .switchIfEmpty(Mono.error(new AnnouncementNotFoundException(id)))
        .flatMap(repository::delete)
        .then();
  }

  public Mono<Long> purgeExpired() {
    Instant now = clock.instant();
    return repository.findByActiveTrueOrderByCreatedAtDesc()
        .filter(row -> row.getExpiresAt() != null && !row.getExpiresAt().isAfter(now))
        .flatMap(row -> {
          row.setActive(false);
          return repository.save(row);
        })
        .count();
  }

  private static boolean hasControlChars(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < 0x20 && c != '\t') {
        return true;
      }
      if (c == 0x7F) {
        return true;
      }
    }
    return false;
  }
}
