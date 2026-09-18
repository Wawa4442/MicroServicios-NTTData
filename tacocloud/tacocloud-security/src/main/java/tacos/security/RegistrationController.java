package tacos.security;

import java.net.URI;

import javax.validation.Valid;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.User;
import tacos.data.UserRepository;

/**
 * Reactive self-registration. The username and the email are unique (enforced
 * by unique MongoDB indexes); a duplicate surfaces as a 409 CONFLICT through
 * the global Problem Detail handler instead of silently overwriting data.
 * Passwords are delegated to the {@code {bcrypt}} delegating encoder.
 */
@RestController
@RequestMapping("/register")
public class RegistrationController {

  private final UserRepository userRepo;
  private final PasswordEncoder passwordEncoder;

  public RegistrationController(
      UserRepository userRepo, PasswordEncoder passwordEncoder) {
    this.userRepo = userRepo;
    this.passwordEncoder = passwordEncoder;
  }

  @GetMapping
  public Mono<ResponseEntity<Void>> registerForm() {
    return Mono.just(ResponseEntity.ok().build());
  }

  @PostMapping
  public Mono<ResponseEntity<Void>> processRegistration(
      @ModelAttribute @Valid RegistrationForm form) {
    return userRepo.existsByUsername(form.getUsername())
        .flatMap(usernameTaken -> {
          if (usernameTaken) {
            return Mono.error(new DuplicateKeyException(
                "username '" + form.getUsername() + "' is already registered"));
          }
          return userRepo.existsByEmail(form.getEmail());
        })
        .flatMap(emailTaken -> {
          if (emailTaken) {
            return Mono.error(new DuplicateKeyException(
                "email '" + form.getEmail() + "' is already registered"));
          }
          User user = form.toUser(passwordEncoder);
          return userRepo.save(user);
        })
        .map(saved -> ResponseEntity.status(HttpStatus.SEE_OTHER)
            .location(URI.create("/login")).<Void>build());
  }

}