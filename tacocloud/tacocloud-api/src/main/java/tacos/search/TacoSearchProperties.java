package tacos.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * Search limits as configuration (TC-19). Every value has a safe default in
 * code, so the catalog is browsable even if nothing is configured; the YAML
 * block only exists to document how an operator would tune it.
 */
@Component
@ConfigurationProperties(prefix = "tacos.search")
@Data
public class TacoSearchProperties {

  /** Page size used when the client does not ask for one. */
  private int defaultSize = 20;

  /** Hard ceiling on {@code size}; anything larger is a 400. */
  private int maxSize = 100;

  /**
   * Deepest reachable offset ({@code page * size}). Beyond it, offset paging
   * stops being a reasonable way to ask the database for a page.
   */
  private int maxOffset = 10_000;

  /**
   * Longest accepted free-text term. Bounding the term is half of the
   * protection against an expensive scan; the other half is that the term is
   * compiled as a literal, never as a pattern the client can shape.
   */
  private int maxTextLength = 40;

  /** Sort fields a client may name. Anything else is a 400, never a guess. */
  private List<String> sortableFields =
      new ArrayList<>(Arrays.asList("createdAt", "name"));

  /** Sort applied when the client does not ask for one: newest first. */
  private String defaultSort = "createdAt,desc";

  /**
   * Field always appended as the last sort key. Two tacos created in the same
   * millisecond would otherwise be free to swap places between two requests,
   * which is exactly how a customer ends up seeing the same taco twice and
   * another one never at all.
   */
  private String stableSortField = "id";

}
