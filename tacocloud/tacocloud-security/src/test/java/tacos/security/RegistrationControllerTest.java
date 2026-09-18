package tacos.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.User;
import tacos.data.UserRepository;

public class RegistrationControllerTest {

  private static final PasswordEncoder ENCODER =
      PasswordEncoderFactories.createDelegatingPasswordEncoder();

  private UserRepository userRepo;
  private RegistrationController controller;

  @BeforeEach
  public void setup() {
    userRepo = Mockito.mock(UserRepository.class);
    controller = new RegistrationController(userRepo, ENCODER);
  }

  @Test
  public void tc10_register_encodesPasswordAsBcrypt_andSavesAndRedirects() {
    when(userRepo.existsByUsername("newbie")).thenReturn(Mono.just(false));
    when(userRepo.existsByEmail("newbie@taco.cl")).thenReturn(Mono.just(false));
    when(userRepo.save(any(User.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    RegistrationForm form = new RegistrationForm();
    form.setUsername("newbie");
    form.setPassword("secret-123");
    form.setFullname("New User");
    form.setEmail("newbie@taco.cl");

    StepVerifier.create(controller.processRegistration(form))
        .assertNext(response -> {
          assertEquals(HttpStatus.SEE_OTHER, response.getStatusCode());
        })
        .verifyComplete();

    org.mockito.ArgumentCaptor<User> captor =
        org.mockito.ArgumentCaptor.forClass(User.class);
    verify(userRepo).save(captor.capture());
    User saved = captor.getValue();
    assertTrue(saved.getPassword().startsWith("{bcrypt}"),
        "the stored password must carry the delegating {bcrypt} prefix");
    assertEquals("newbie", saved.getUsername());
  }

  @Test
  public void tc10_register_duplicateUsername_isRejectedWith409Semantics() {
    when(userRepo.existsByUsername("taken")).thenReturn(Mono.just(true));

    RegistrationForm form = new RegistrationForm();
    form.setUsername("taken");
    form.setPassword("secret-123");
    form.setFullname("Taker");
    form.setEmail("taker@taco.cl");

    StepVerifier.create(controller.processRegistration(form))
        .expectError(DuplicateKeyException.class)
        .verify();

    verify(userRepo, never()).save(any(User.class));
  }

  @Test
  public void tc10_register_duplicateEmail_isRejectedWith409Semantics() {
    when(userRepo.existsByUsername("fresh")).thenReturn(Mono.just(false));
    when(userRepo.existsByEmail("busy@taco.cl")).thenReturn(Mono.just(true));

    RegistrationForm form = new RegistrationForm();
    form.setUsername("fresh");
    form.setPassword("secret-123");
    form.setFullname("Fresh");
    form.setEmail("busy@taco.cl");

    StepVerifier.create(controller.processRegistration(form))
        .expectError(DuplicateKeyException.class)
        .verify();

    verify(userRepo, never()).save(any(User.class));
  }

  @Test
  public void tc10_defaultRoleIsUser_whileAdminCanBeAssigned() {
    User user = new User("u", "pw", "Full", "s", "c", "TX", "78701",
        "555", "u@taco.cl");
    assertEquals("ROLE_USER", user.getRole());
    assertEquals("ROLE_USER",
        user.getAuthorities().iterator().next().getAuthority());

    user.setRole("ROLE_ADMIN");
    assertEquals("ROLE_ADMIN",
        user.getAuthorities().iterator().next().getAuthority());
  }

}