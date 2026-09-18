package tacos.web.api;

/**
 * Identity of the caller performing an order mutation.
 *
 * <p>When no authentication is present (the current security posture is
 * {@code permitAll}, resolved in TC-11) the caller is {@link #anonymous()}.
 * Once authentication exists, the caller carries the authenticated {@code userId}
 * or the {@code ADMIN} privilege, and ownership is enforced against the order's
 * embedded owner.
 */
class CallerIdentity {

  private final String userId;
  private final boolean admin;

  private CallerIdentity(String userId, boolean admin) {
    this.userId = userId;
    this.admin = admin;
  }

  static CallerIdentity anonymous() {
    return new CallerIdentity(null, false);
  }

  static CallerIdentity user(String userId) {
    return new CallerIdentity(userId, false);
  }

  static CallerIdentity admin() {
    return new CallerIdentity(null, true);
  }

  String getUserId() {
    return userId;
  }

  boolean isAdmin() {
    return admin;
  }

}