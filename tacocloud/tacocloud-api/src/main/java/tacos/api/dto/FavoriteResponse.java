package tacos.api.dto;

import java.util.Date;

import lombok.Value;

import tacos.Favorite;
import tacos.Taco;

/**
 * One saved favorite as the customer sees it (TC-21).
 *
 * <p>{@code orphaned} is the answer to "what happens when the taco I saved is
 * deleted". The row is kept, not silently dropped, and the flag lets the UI say
 * "this one is no longer on the menu" instead of the customer discovering the
 * disappearance. {@code tacoName} is resolved live from the catalog rather than
 * copied into the document, so a renamed taco shows its new name.
 */
@Value
public class FavoriteResponse {

  private final String tacoId;
  private final String tacoName;
  private final boolean orphaned;
  private final Date savedAt;

  public static FavoriteResponse of(Favorite favorite, Taco taco) {
    boolean orphaned = taco == null;
    return new FavoriteResponse(favorite.getTacoId(),
        orphaned ? null : taco.getName(), orphaned, favorite.getSavedAt());
  }

}
