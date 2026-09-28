package tacos;

import java.util.Date;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A taco a customer marked as a favorite (TC-21).
 *
 * <p>Two deliberate design decisions:
 * <ul>
 *   <li>the document holds {@code userId} and {@code tacoId} only — never the
 *       whole {@link User} and never a copy of the taco. Embedding the user
 *       would duplicate a password hash per favorite and make "my favorites"
 *       leak somebody else's profile the moment a collection is exported;</li>
 *   <li>the unique compound index is the concurrency guarantee. Two parallel
 *       PUTs for the same pair cannot produce two rows, so "add favorite" is
 *       idempotent at the database level and not merely at the service
 *       level.</li>
 * </ul>
 *
 * <p>A favorite is not cascaded when a taco disappears: the row stays and the
 * read side reports it as orphaned, so a customer can see that a favorite they
 * saved no longer exists instead of silently losing it.
 */
@Data
@NoArgsConstructor(access = AccessLevel.PRIVATE, force = true)
@Document("favorites")
@CompoundIndex(name = "favorite_user_taco_unique",
    def = "{'userId': 1, 'tacoId': 1}", unique = true)
public class Favorite {

  @Id
  private String id;

  /** Id of the authenticated customer. Never read from the request. */
  @Indexed
  private String userId;

  private String tacoId;

  private Date savedAt = new Date();

  public Favorite(String userId, String tacoId) {
    this.userId = userId;
    this.tacoId = tacoId;
  }

}
