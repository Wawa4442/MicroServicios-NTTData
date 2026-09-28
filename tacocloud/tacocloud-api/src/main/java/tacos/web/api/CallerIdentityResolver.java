package tacos.web.api;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;
import tacos.User;

/**
 * Turns the reactive security context into an application identity.
 *
 * <p>Extracted from the order controller because Laboratorio 4 adds several
 * endpoints whose whole contract is "this is <em>your</em> data". Having one
 * resolver means there is exactly one place where the principal is read, and
 * therefore exactly one place to audit.
 */
@Component
public class CallerIdentityResolver {

  /**
   * The caller, which may be {@link CallerIdentity#anonymous()} when no
   * principal is present. Used by the order routes, whose historical posture
   * keeps working without authentication.
   */
  public Mono<CallerIdentity> identity() {
    return ReactiveSecurityContextHolder.getContext()
        .map(SecurityContext::getAuthentication)
        .filter(auth -> auth != null && auth.isAuthenticated())
        .map(Authentication::getPrincipal)
        .map(this::toCallerIdentity)
        .defaultIfEmpty(CallerIdentity.anonymous());
  }

  /**
   * The caller, but never anonymous. For routes that act on somebody's own
   * account or spend their money: a reorder, for instance, must not fall back
   * to the order routes' permissive posture, or an unauthenticated caller could
   * place orders against somebody else's order id.
   */
  public Mono<CallerIdentity> required() {
    return identity()
        .filter(caller -> caller.getUserId() != null)
        .switchIfEmpty(Mono.error(new AuthenticationRequiredException(
            "This endpoint acts on your own account. Sign in and try again.")));
  }

  /**
   * The authenticated customer's id, for the {@code /api/users/me/**} routes.
   *
   * <p>These endpoints have no way to express "whose favorites?" other than by
   * asking the security context, so a missing principal is a 401 rather than a
   * best-effort guess. There is deliberately no fallback to a request
   * parameter: a {@code ?userId=} here would let any caller read any other
   * customer's favorites and order history.
   *
   * <p>The filter runs before the map on purpose. Mapping an anonymous caller
   * would hand {@code null} to Reactor, which reports a null mapping as an
   * internal error — the request would come back as a 500 for a missing
   * login, which is both wrong and a lot harder to diagnose.
   */
  public Mono<String> requiredUserId() {
    return identity()
        .filter(caller -> caller.getUserId() != null)
        .map(CallerIdentity::getUserId)
        .switchIfEmpty(Mono.error(new AuthenticationRequiredException(
            "This endpoint acts on your own account. Sign in and try again.")));
  }

  private CallerIdentity toCallerIdentity(Object principal) {
    if (principal instanceof User) {
      User user = (User) principal;
      boolean admin = user.getAuthorities().stream()
          .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
      boolean kitchen = user.getAuthorities().stream()
          .anyMatch(a -> a.getAuthority().equals("ROLE_KITCHEN"));
      // An operator is still a person: keeping the id lets them use their own
      // favorites and history instead of being told they are anonymous.
      if (admin && kitchen) {
        return CallerIdentity.adminKitchen(user.getId());
      }
      if (admin) {
        return CallerIdentity.admin(user.getId());
      }
      if (kitchen) {
        return CallerIdentity.kitchen(user.getId());
      }
      return CallerIdentity.user(user.getId());
    }
    return CallerIdentity.anonymous();
  }

}
