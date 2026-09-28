package tacos.web.api;

import javax.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.api.dto.ReorderRequest;
import tacos.api.dto.ReorderResponse;
import tacos.reorder.ReorderService;

/**
 * {@code POST /api/orders/{id}/reorder} (TC-24).
 *
 * <p>The status code follows what actually happened. A placed reorder created a
 * resource, so it answers 201. A quote did not, so it answers 200 with
 * {@code status: QUOTE} and nothing persisted — the customer has to send the
 * same request again with {@code confirmPriceChange} to go through. Returning
 * 201 for a quote would tell the client there is an order when there is not.
 */
@RestController
@RequestMapping(path = "/api/orders", produces = "application/json")
@CrossOrigin(origins = "http://localhost:8080")
public class ReorderController {

  private final ReorderService reorders;
  private final CallerIdentityResolver identities;

  public ReorderController(ReorderService reorders, CallerIdentityResolver identities) {
    this.reorders = reorders;
    this.identities = identities;
  }

  @PostMapping(path = "/{orderId}/reorder", consumes = "application/json")
  public Mono<ResponseEntity<ReorderResponse>> reorder(
      @PathVariable("orderId") String orderId,
      @Valid @RequestBody ReorderRequest request) {
    return identities.required()
        .flatMap(caller -> reorders.reorder(orderId, request, caller))
        .map(ReorderController::statusOf);
  }

  private static ResponseEntity<ReorderResponse> statusOf(ReorderResponse response) {
    return response.getStatus() == ReorderResponse.Status.CONFIRMED
        ? ResponseEntity.status(HttpStatus.CREATED).body(response)
        : ResponseEntity.ok(response);
  }

}
