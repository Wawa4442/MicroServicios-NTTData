package tacos.announcements;

import javax.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.web.api.CallerIdentityResolver;

/**
 * Operator board (TC-33).
 *
 * <p>Lives under {@code /api/admin/**}, so only ADMIN writes or reads here
 * (the existing security rule already says so). The public Actuator
 * {@code notes} endpoint is kept as a deprecated read alias; this controller
 * is the write path with stable ids, validation and persistence.
 */
@RestController
@RequestMapping(path = "/api/admin/announcements", produces = "application/json")
public class AdminAnnouncementController {

  private final AnnouncementService announcements;
  private final CallerIdentityResolver identities;

  public AdminAnnouncementController(AnnouncementService announcements,
                                     CallerIdentityResolver identities) {
    this.announcements = announcements;
    this.identities = identities;
  }

  @GetMapping
  public Flux<AnnouncementResponse> list() {
    return announcements.listActive().map(AnnouncementResponse::from);
  }

  @PostMapping(consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<AnnouncementResponse> create(@RequestBody @Valid AnnouncementRequest request) {
    return identities.identity()
        .flatMap(caller -> announcements.create(
            request.getText(), request.getSeverity(),
            request.getExpiresAt(), caller.auditLabel()))
        .map(AnnouncementResponse::from);
  }

  @DeleteMapping("/{id}")
  public Mono<Void> delete(@PathVariable("id") String id) {
    return announcements.deleteById(id);
  }
}
