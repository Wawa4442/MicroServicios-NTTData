package tacos.announcements;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class AnnouncementValidationException extends RuntimeException {
  public AnnouncementValidationException(String message) {
    super(message);
  }
}
