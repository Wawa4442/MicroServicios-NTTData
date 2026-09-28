package tacos;

import java.util.Date;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AccessLevel;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One customer's score for one taco (TC-22). Like {@link Favorite} it stores
 * identifiers, never the embedded user, and it is protected by a unique
 * compound index so "one vote per customer per taco" is a database fact
 * instead of an application convention.
 *
 * <p>Two indexes, because there are two access paths: the write/upsert path
 * looks a row up by {@code (userId, tacoId)} and the ranking aggregation
 * groups by {@code tacoId} alone.
 */
@Data
@NoArgsConstructor(access = AccessLevel.PRIVATE, force = true)
@Document("ratings")
@CompoundIndexes({
    @CompoundIndex(name = "rating_user_taco_unique",
        def = "{'userId': 1, 'tacoId': 1}", unique = true),
    @CompoundIndex(name = "rating_taco_idx", def = "{'tacoId': 1, 'score': 1}")
})
public class TacoRating {

  @Id
  private String id;

  /** Id of the authenticated customer. Never read from the request. */
  private String userId;

  private String tacoId;

  /** 1 to 5, validated by the service against configurable bounds. */
  private int score;

  private Date createdAt = new Date();

  private Date updatedAt = new Date();

  public TacoRating(String userId, String tacoId, int score) {
    this.userId = userId;
    this.tacoId = tacoId;
    this.score = score;
  }

}
