package tacos.search;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.api.dto.PageResponse;

/**
 * Catalog search use case (TC-19).
 *
 * <p>The service is intentionally thin. Parsing and validating the request
 * already happened in {@link TacoSearchQuery#of}, and executing it belongs to
 * {@link TacoSearchPort}; what is left is turning a {@link Page} into the
 * transport envelope, which is the one piece of policy the adapter has no
 * business knowing about.
 */
@Service
public class TacoSearchService {

  private final TacoSearchPort port;

  public TacoSearchService(TacoSearchPort port) {
    this.port = port;
  }

  public Mono<PageResponse<Taco>> search(TacoSearchQuery query) {
    return port.search(query).map(page -> PageResponse.of(
        page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements()));
  }

}
