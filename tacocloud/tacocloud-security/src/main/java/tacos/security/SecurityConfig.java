package tacos.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive
                                    .EnableWebFluxSecurity;
import org.springframework.security.config.web.server
                                    .ServerHttpSecurity;
import org.springframework.security.crypto.factory
                                    .PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Reactive, deny-by-default security configuration.
 *
 * <p>The default posture is {@code authenticated()}: every route must be
 * explicitly granted. Roles are USER (the registered customer), KITCHEN (the
 * kitchen gateway) and ADMIN (the operator). The password encoder is a
 * delegating encoder so stored secrets carry the {@code {bcrypt}} prefix and
 * plain-text "encoders" are no longer used anywhere.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

  @Bean
  public SecurityWebFilterChain securityWebFilterChain(
      ServerHttpSecurity http) {
    return http
      .authorizeExchange(exchanges -> exchanges
        // SPA/CORS support: preflight is always allowed.
        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
        // Public pages and static assets.
        .pathMatchers("/", "/index.html", "/favicon.ico",
            "/login", "/register", "/register/**",
            "/assets/**", "/webjars/**",
            "/*.js", "/*.css", "/*.ico", "/*.map",
            "/*.png", "/*.jpg", "/*.svg", "/*.woff2",
            "/actuator/health").permitAll()
        // Versioned contract is readable by any authenticated caller; the
        // version filter rewrites v1 to the same handlers, so the rules below
        // mirror for both prefixes (TC-35).
        .pathMatchers(HttpMethod.GET, "/api/openapi.yaml", "/api/v1/openapi.yaml")
            .authenticated()
        // Kitchen consumes recent orders through a dedicated gateway.
        // Operators share it (TC-25/TC-26): an ADMIN advancing a stuck
        // ticket is the same lifecycle move, not a different resource.
        .pathMatchers("/api/kitchen/**", "/api/v1/kitchen/**").hasAnyRole("KITCHEN", "ADMIN")
        // Operator-only catalog and inventory administration.
        .pathMatchers("/api/admin/**", "/api/v1/admin/**").hasRole("ADMIN")
        // Laboratorio 4: a customer's own favorites and order history. The
        // "me" in the path is the only selector there is, so this rule is what
        // keeps one customer out of another's data.
        .pathMatchers("/api/users/me/**", "/api/v1/users/me/**").hasAnyRole("USER", "ADMIN")
        // Order lifecycle belongs to customers, the kitchen and operators;
        // rule-based ownership is enforced in OrderApiService.
        .pathMatchers("/api/orders/**", "/api/v1/orders/**").hasAnyRole("USER", "KITCHEN", "ADMIN")
        // Coupon validation belongs to whoever is building an order.
        .pathMatchers("/api/coupons/**", "/api/v1/coupons/**").hasAnyRole("USER", "KITCHEN", "ADMIN")
        // Taco Physics design validation runs before any order is created.
        .pathMatchers(HttpMethod.POST, "/api/tacos/validate", "/api/v1/tacos/validate")
            .hasAnyRole("USER", "KITCHEN", "ADMIN")
        // A rating is a customer's statement about the catalog, so it is their
        // own write and not catalog administration. It has to be named before
        // the ADMIN-only catch-all below, because authorizeExchange stops at the
        // first matching rule: without it, a customer could not rate.
        .pathMatchers(HttpMethod.PUT, "/api/tacos/*/rating", "/api/v1/tacos/*/rating")
            .hasAnyRole("USER", "ADMIN")
        // The catalog can be read by any authenticated principal (the SPA
        // needs it to design tacos) but only administered by operators.
        .pathMatchers(HttpMethod.GET, "/api/ingredients/**", "/api/v1/ingredients/**")
            .hasAnyRole("USER", "KITCHEN", "ADMIN")
        .pathMatchers(HttpMethod.GET, "/api/tacos/**", "/api/v1/tacos/**")
            .hasAnyRole("USER", "KITCHEN")
        .pathMatchers("/api/ingredients/**", "/api/v1/ingredients/**").hasRole("ADMIN")
        .pathMatchers("/api/tacos/**", "/api/v1/tacos/**").hasRole("ADMIN")
        // Legacy Data REST and management endpoints are operator-only.
        .pathMatchers("/data-api/**").hasRole("ADMIN")
        .pathMatchers("/actuator/**").hasRole("ADMIN")
        // Everything else requires an authenticated principal.
        .anyExchange().authenticated())
      .httpBasic()
      .and()
        .formLogin()
      .and()
        .csrf().disable()
      .build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    // Delegating encoder: verification supports {bcrypt}, {noop}, ... while
    // new passwords are always hashed as {bcrypt}. NoOpPasswordEncoder is gone.
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

}