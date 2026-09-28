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
  private final boolean kitchen;

  private CallerIdentity(String userId, boolean admin, boolean kitchen) {
    this.userId = userId;
    this.admin = admin;
    this.kitchen = kitchen;
  }

  public static CallerIdentity anonymous() {
    return new CallerIdentity(null, false, false);
  }

  public static CallerIdentity user(String userId) {
    return new CallerIdentity(userId, false, false);
  }

  public static CallerIdentity admin(String userId) {
    return new CallerIdentity(userId, true, false);
  }

  public static CallerIdentity kitchen(String userId) {
    return new CallerIdentity(userId, false, true);
  }

  public static CallerIdentity adminKitchen(String userId) {
    return new CallerIdentity(userId, true, true);
  }

  public String getUserId() {
    return userId;
  }

  public boolean isAdmin() {
    return admin;
  }

  public boolean isKitchen() {
    return kitchen;
  }

  /**
   * Short label for audit trails (TC-25): the user id when known, otherwise
   * the role that performed the change. Never a password or token.
   */
  public String auditLabel() {
    if (userId != null) {
      return userId;
    }
    if (admin) {
      return "admin";
    }
    if (kitchen) {
      return "kitchen";
    }
    return "anonymous";
  }

}
