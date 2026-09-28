package tacos.web.api;

/**
 * Identity of the caller performing a request.
 *
 * <p>When no authentication is present the caller is {@link #anonymous()};
 * once authentication exists the identity carries the authenticated
 * {@code userId} and, for operators, the {@code admin} privilege alongside it.
 * Keeping both matters: an operator is still a person with a personal order
 * history, and collapsing the two into one flag would make those endpoints
 * unusable for them.
 */
public final class CallerIdentity {

  private final String userId;
  private final boolean admin;

  private CallerIdentity(String userId, boolean admin) {
    this.userId = userId;
    this.admin = admin;
  }

  public static CallerIdentity anonymous() {
    return new CallerIdentity(null, false);
  }

  public static CallerIdentity user(String userId) {
    return new CallerIdentity(userId, false);
  }

  public static CallerIdentity admin(String userId) {
    return new CallerIdentity(userId, true);
  }

  public String getUserId() {
    return userId;
  }

  public boolean isAdmin() {
    return admin;
  }

}
