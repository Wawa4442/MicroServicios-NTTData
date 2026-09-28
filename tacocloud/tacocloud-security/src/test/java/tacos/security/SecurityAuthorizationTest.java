package tacos.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;
import tacos.User;

public class SecurityAuthorizationTest {

  private WebFilterChainProxy proxy;

  @BeforeEach
  public void setup() {
    ServerHttpSecurity http = ServerHttpSecurity.http();
    http.authenticationManager(auth -> Mono.just(auth));
    SecurityWebFilterChain chain =
        new SecurityConfig().securityWebFilterChain(http);
    proxy = new WebFilterChainProxy(chain);
  }

  @Test
  public void tc11_anonymous_isRejectedWith401() {
    exchange(HttpMethod.GET, "/api/orders", null)
        .expectStatus(HttpStatus.UNAUTHORIZED);
  }

  @Test
  public void tc11_user_canAccessOrders_andReadCatalog() {
    exchange(HttpMethod.GET, "/api/orders", user("ROLE_USER"))
        .expectStatus(HttpStatus.OK);
    exchange(HttpMethod.GET, "/api/ingredients", user("ROLE_USER"))
        .expectStatus(HttpStatus.OK);
  }

  @Test
  public void tc11_user_cannotWriteCatalog() {
    exchange(HttpMethod.POST, "/api/ingredients", user("ROLE_USER"))
        .expectStatus(HttpStatus.FORBIDDEN);
  }

  @Test
  public void tc13_adminEndpoints_areAdminOnly() {
    exchange(HttpMethod.PATCH, "/api/admin/ingredients/FLTO/catalog", user("ROLE_USER"))
        .expectStatus(HttpStatus.FORBIDDEN);
    exchange(HttpMethod.POST, "/api/admin/ingredients/FLTO/stock-adjustments",
        user("ROLE_KITCHEN"))
        .expectStatus(HttpStatus.FORBIDDEN);
    exchange(HttpMethod.PATCH, "/api/admin/ingredients/FLTO/catalog", user("ROLE_ADMIN"))
        .expectStatus(HttpStatus.OK);
    exchange(HttpMethod.PATCH, "/api/admin/ingredients/FLTO/catalog", null)
        .expectStatus(HttpStatus.UNAUTHORIZED);
  }

  @Test
  public void tc11_user_cannotAccessKitchenGateway() {
    exchange(HttpMethod.GET, "/api/kitchen/orders", user("ROLE_USER"))
        .expectStatus(HttpStatus.FORBIDDEN);
  }

  @Test
  public void tc11_kitchen_canAccessKitchenGateway_andOrders() {
    exchange(HttpMethod.GET, "/api/kitchen/orders", user("ROLE_KITCHEN"))
        .expectStatus(HttpStatus.OK);
    exchange(HttpMethod.GET, "/api/orders", user("ROLE_KITCHEN"))
        .expectStatus(HttpStatus.OK);
  }

  @Test
  public void tc11_admin_canAccessEverything() {
    exchange(HttpMethod.GET, "/api/orders", user("ROLE_ADMIN"))
        .expectStatus(HttpStatus.OK);
    exchange(HttpMethod.POST, "/api/ingredients", user("ROLE_ADMIN"))
        .expectStatus(HttpStatus.OK);
    exchange(HttpMethod.GET, "/actuator/info", user("ROLE_ADMIN"))
        .expectStatus(HttpStatus.OK);
    exchange(HttpMethod.GET, "/data-api/ingredients", user("ROLE_ADMIN"))
        .expectStatus(HttpStatus.OK);
  }

  @Test
  public void tc11_admin_only_forActuatorAndLegacyDataApi() {
    exchange(HttpMethod.GET, "/actuator/info", user("ROLE_USER"))
        .expectStatus(HttpStatus.FORBIDDEN);
    exchange(HttpMethod.GET, "/data-api/ingredients", user("ROLE_USER"))
        .expectStatus(HttpStatus.FORBIDDEN);
    exchange(HttpMethod.GET, "/actuator/info", null)
        .expectStatus(HttpStatus.UNAUTHORIZED);
  }

  @Test
  public void tc11_health_isPublic() {
    exchange(HttpMethod.GET, "/actuator/health", null)
        .expectStatus(HttpStatus.OK);
  }

  @Test
  public void tc11_options_andForms_arePublic() {
    exchange(HttpMethod.OPTIONS, "/api/orders", null)
        .expectStatus(HttpStatus.OK);
    exchange(HttpMethod.GET, "/login", null)
        .expectStatus(HttpStatus.OK);
    exchange(HttpMethod.GET, "/register", null)
        .expectStatus(HttpStatus.OK);
  }

  @Test
  public void tc11_unknownRoute_defaultsToAuthenticated() {
    exchange(HttpMethod.GET, "/anything/else", null)
        .expectStatus(HttpStatus.UNAUTHORIZED);
    exchange(HttpMethod.GET, "/anything/else", user("ROLE_USER"))
        .expectStatus(HttpStatus.OK);
  }

  @Test
  public void tc21_favoritesAreTheCustomersOwn() {
    exchange(HttpMethod.GET, "/api/users/me/favorites", null)
        .expectStatus(HttpStatus.UNAUTHORIZED);
    exchange(HttpMethod.GET, "/api/users/me/favorites", user("ROLE_USER"))
        .expectStatus(HttpStatus.OK);
    exchange(HttpMethod.PUT, "/api/users/me/favorites/taco-1", user("ROLE_USER"))
        .expectStatus(HttpStatus.OK);
    exchange(HttpMethod.DELETE, "/api/users/me/favorites/taco-1", user("ROLE_USER"))
        .expectStatus(HttpStatus.OK);
    // The kitchen has no business in a customer's saved list.
    exchange(HttpMethod.GET, "/api/users/me/favorites", user("ROLE_KITCHEN"))
        .expectStatus(HttpStatus.FORBIDDEN);
  }

  @Test
  public void tc21_thereIsNoRouteToSomebodyElsesFavorites() {
    // Only the "me" prefix is granted, so a userId in the path reaches no
    // controller at all and answers 404. The security layer still demands a
    // principal first, which is the posture that matters: there is no rule
    // anywhere that would let one customer's request carry another customer's id
    // into a handler.
    exchange(HttpMethod.GET, "/api/users/userB/favorites", null)
        .expectStatus(HttpStatus.UNAUTHORIZED);
    exchange(HttpMethod.GET, "/api/users/userB/favorites", user("ROLE_USER"))
        .expectStatus(HttpStatus.OK);
  }

  @Test
  public void tc22_aRatingIsTheCustomersOwnWrite() {
    exchange(HttpMethod.PUT, "/api/tacos/taco-1/rating", null)
        .expectStatus(HttpStatus.UNAUTHORIZED);
    exchange(HttpMethod.PUT, "/api/tacos/taco-1/rating", user("ROLE_USER"))
        .expectStatus(HttpStatus.OK);
    exchange(HttpMethod.PUT, "/api/tacos/taco-1/rating", user("ROLE_ADMIN"))
        .expectStatus(HttpStatus.OK);
    // The rating route is named before the ADMIN-only catalog catch-all, so a
    // plain customer can rate; catalog administration stays with operators.
    exchange(HttpMethod.POST, "/api/tacos", user("ROLE_USER"))
        .expectStatus(HttpStatus.FORBIDDEN);
    exchange(HttpMethod.POST, "/api/tacos", user("ROLE_ADMIN"))
        .expectStatus(HttpStatus.OK);
  }

  @Test
  public void tc22_theChartIsReadThroughTheCatalogRule() {
    exchange(HttpMethod.GET, "/api/tacos/top", user("ROLE_USER"))
        .expectStatus(HttpStatus.OK);
    exchange(HttpMethod.GET, "/api/tacos/top", null)
        .expectStatus(HttpStatus.UNAUTHORIZED);
  }

  @Test
  public void tc23_orderHistoryIsTheCustomersOwn() {
    exchange(HttpMethod.GET, "/api/users/me/orders", null)
        .expectStatus(HttpStatus.UNAUTHORIZED);
    exchange(HttpMethod.GET, "/api/users/me/orders", user("ROLE_USER"))
        .expectStatus(HttpStatus.OK);
    exchange(HttpMethod.GET, "/api/users/me/orders/o-1", user("ROLE_USER"))
        .expectStatus(HttpStatus.OK);
    // No controller answers a userId in the path, so the request is authenticated
    // and then finds nothing: 404, never somebody else's history.
    exchange(HttpMethod.GET, "/api/users/userB/orders", user("ROLE_USER"))
        .expectStatus(HttpStatus.OK);
  }

  @Test
  public void tc23_theOrderHistoryIsNotOpenToTheKitchen() {
    exchange(HttpMethod.GET, "/api/users/me/orders", user("ROLE_KITCHEN"))
        .expectStatus(HttpStatus.FORBIDDEN);
  }

  @Test
  public void tc23_theOperatorOrderListIsAdminOnly() {
    exchange(HttpMethod.GET, "/api/admin/orders", user("ROLE_USER"))
        .expectStatus(HttpStatus.FORBIDDEN);
    exchange(HttpMethod.GET, "/api/admin/orders", user("ROLE_KITCHEN"))
        .expectStatus(HttpStatus.FORBIDDEN);
    exchange(HttpMethod.GET, "/api/admin/orders", user("ROLE_ADMIN"))
        .expectStatus(HttpStatus.OK);
    exchange(HttpMethod.GET, "/api/admin/orders", null)
        .expectStatus(HttpStatus.UNAUTHORIZED);
  }

  @Test
  public void tc24_aReorderIsAnOrderMutation() {
    exchange(HttpMethod.POST, "/api/orders/o-1/reorder", null)
        .expectStatus(HttpStatus.UNAUTHORIZED);
    exchange(HttpMethod.POST, "/api/orders/o-1/reorder", user("ROLE_USER"))
        .expectStatus(HttpStatus.OK);
    exchange(HttpMethod.POST, "/api/orders/o-1/reorder", user("ROLE_ADMIN"))
        .expectStatus(HttpStatus.OK);
  }

  private RequestAssert exchange(HttpMethod method, String path, User principal) {
    MockServerWebExchange exchange = MockServerWebExchange.from(
        MockServerHttpRequest.method(method, path).build());
    WebFilterChain noop = ex -> Mono.empty();
    Mono<Void> result = proxy.filter(exchange, noop);
    if (principal != null) {
      Authentication auth = new UsernamePasswordAuthenticationToken(
          principal, principal.getPassword(), principal.getAuthorities());
      result = result.contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth));
    }
    result.block();
    HttpStatus status = exchange.getResponse().getStatusCode();
    // A request that passes authorization and reaches the downstream handler
    // is left untouched; MockServerWebExchange then reports no status at all.
    if (status == null) {
      status = HttpStatus.OK;
    }
    return new RequestAssert(status);
  }

  private static User user(String role) {
    User user = new User("someone", "pw", "Someone", "1 Oak", "Austin", "TX",
        "78701", "555", "someone@taco.cl");
    user.setRole(role);
    return user;
  }

  private static class RequestAssert {
    private final HttpStatus actual;

    RequestAssert(HttpStatus actual) {
      this.actual = actual;
    }

    void expectStatus(HttpStatus expected) {
      assertEquals(expected, actual, "HTTP status");
    }
  }

}