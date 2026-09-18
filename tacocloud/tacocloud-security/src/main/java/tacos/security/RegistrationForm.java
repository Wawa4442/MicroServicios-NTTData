package tacos.security;

import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

import org.springframework.security.crypto.password.PasswordEncoder;

import lombok.Data;
import tacos.User;

@Data
public class RegistrationForm {

  @NotBlank(message = "username is required")
  @Size(min = 4, max = 50, message = "username must be between 4 and 50 characters")
  private String username;

  @NotBlank(message = "password is required")
  @Size(min = 6, message = "password must be at least 6 characters")
  private String password;

  @NotBlank(message = "fullname is required")
  private String fullname;

  private String street;
  private String city;
  private String state;
  private String zip;
  private String phone;

  @NotBlank(message = "email is required")
  @Email(message = "email must be a valid address")
  private String email;

  public User toUser(PasswordEncoder passwordEncoder) {
    return new User(
        username, passwordEncoder.encode(password),
        fullname, street, city, state, zip, phone, email);
  }

}