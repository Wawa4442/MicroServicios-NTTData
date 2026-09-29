package tacos.announcements;

import java.time.Instant;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

import lombok.Data;

/**
 * What an operator sends to leave an announcement.
 */
@Data
public class AnnouncementRequest {

  @NotBlank(message = "text is required")
  @Size(max = 500, message = "text must be at most 500 characters")
  private String text;

  private AnnouncementSeverity severity;

  private Instant expiresAt;
}
