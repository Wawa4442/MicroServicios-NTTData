package tacos.actuator;

import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

@Component
@Endpoint(id = "notes", enableByDefault = true)
public class NotesEndpoint {

  private final tacos.announcements.AnnouncementService announcements;

  public NotesEndpoint(tacos.announcements.AnnouncementService announcements) {
    this.announcements = announcements;
  }

  @ReadOperation
  public reactor.core.publisher.Mono<java.util.List<tacos.announcements.AnnouncementResponse>> notes() {
    return announcements.listActive()
        .map(tacos.announcements.AnnouncementResponse::from)
        .collectList();
  }

  @WriteOperation
  public reactor.core.publisher.Mono<tacos.announcements.AnnouncementResponse> addNote(String text) {
    return announcements.create(text, tacos.announcements.AnnouncementSeverity.INFO,
        null, "actuator").map(tacos.announcements.AnnouncementResponse::from);
  }

  @DeleteOperation
  public reactor.core.publisher.Mono<Void> deleteNote(String id) {
    return announcements.deleteById(id);
  }
}
