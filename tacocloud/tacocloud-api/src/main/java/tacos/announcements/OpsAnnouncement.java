package tacos.announcements;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;

/**
 * An operational note the shop keeps across restarts (TC-33).
 *
 * <p>Replaces the old in-memory {@code NotesEndpoint} list, where the "id"
 * was the position in an {@code ArrayList} and everything vanished on
 * restart. Here the id is stable, the text is bounded, and every row knows
 * who left it, how severe it is and until when it matters. Expired rows stop
 * showing up and are purged in the background.
 */
@Data
@Document("opsAnnouncements")
public class OpsAnnouncement {

  @Id
  private String id;

  private String text;

  private AnnouncementSeverity severity = AnnouncementSeverity.INFO;

  private Instant createdAt;

  private Instant expiresAt;

  private String createdBy;

  private boolean active = true;

  @Indexed
  private Instant activeUntil;
}
