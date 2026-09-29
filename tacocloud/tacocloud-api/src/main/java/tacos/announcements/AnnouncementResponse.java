package tacos.announcements;

import java.time.Instant;

import lombok.Data;

/**
 * What readers see. The author label is the audit name, never a secret.
 */
@Data
public class AnnouncementResponse {

  private String id;
  private String text;
  private AnnouncementSeverity severity;
  private Instant createdAt;
  private Instant expiresAt;
  private String createdBy;
  private boolean active;

  public static AnnouncementResponse from(OpsAnnouncement saved) {
    AnnouncementResponse out = new AnnouncementResponse();
    out.setId(saved.getId());
    out.setText(saved.getText());
    out.setSeverity(saved.getSeverity());
    out.setCreatedAt(saved.getCreatedAt());
    out.setExpiresAt(saved.getExpiresAt());
    out.setCreatedBy(saved.getCreatedBy());
    out.setActive(saved.isActive());
    return out;
  }
}
