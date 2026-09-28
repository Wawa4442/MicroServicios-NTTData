package tacos.history;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Page window of the order history (TC-23). The ceiling exists because an
 * order page is the one place where "give me everything" is a natural thing for
 * a client to ask and an expensive thing for the database to answer.
 */
@Component
@ConfigurationProperties(prefix = "tacos.history")
@Data
public class OrderHistoryProperties {

  private int defaultSize = 10;

  private int maxSize = 50;

}
