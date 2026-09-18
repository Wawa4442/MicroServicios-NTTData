package tacos.security;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.ReactiveUserDetailsService;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.
                                       UsernameNotFoundException;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import tacos.data.UserRepository;

@Service
public class UserRepositoryUserDetailsService
        implements ReactiveUserDetailsService {

  private UserRepository userRepo;

  @Autowired
  public UserRepositoryUserDetailsService(UserRepository userRepo) {
    this.userRepo = userRepo;
  }

  @Override
  public Mono<UserDetails> findByUsername(String username) {
    return userRepo.findByUsername(username)
        .cast(UserDetails.class)
        .switchIfEmpty(Mono.error(
            new UsernameNotFoundException(
                "User '" + username + "' not found")));
  }

}