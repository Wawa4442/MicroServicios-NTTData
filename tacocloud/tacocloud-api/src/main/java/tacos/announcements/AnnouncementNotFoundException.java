package tacos.announcements;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class AnnouncementNotFoundException extends RuntimeException {
  public AnnouncementNotFoundException(String id) {
    super("Announcement " + id + " does not exist.");
  }
}
