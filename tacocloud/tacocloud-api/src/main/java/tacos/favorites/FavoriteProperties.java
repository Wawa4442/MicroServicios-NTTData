package tacos.favorites;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Page window of the favorites list (TC-21). Kept in configuration for the same
 * reason as the other lists: the ceiling is a decision about load, and it should
 * be adjustable without touching the controller.
 */
@Component
@ConfigurationProperties(prefix = "tacos.favorites")
@Data
public class FavoriteProperties {

  private int defaultSize = 20;

  private int maxSize = 100;

}
