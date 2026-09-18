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