package tacos.web.api;

import javax.validation.Valid;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.api.dto.RatingRequest;
import tacos.api.dto.RatingResponse;
import tacos.api.dto.TopTacoResponse;
import tacos.rating.TacoRatingService;

import java.util.List;

/**
 * Reputation (TC-22): {@code PUT /api/tacos/{id}/rating} to rate and
 * {@code GET /api/tacos/top} to read the chart.
 *
 * <p>The rating is a PUT and not a POST because it names the resource it
 * creates: the pair (this customer, this taco). Repeating the call replaces
 * the customer's own score instead of adding a second one, which is what makes
 * "I changed my mind" a normal action rather than a special case.
 */
@RestController
@RequestMapping(path = "/api/tacos", produces = "application/json")
@CrossOrigin(origins = "http://localhost:8080")
public class TacoRatingController {

  private final TacoRatingService ratings;
  private final CallerIdentityResolver identities;

  public TacoRatingController(TacoRatingService ratings, CallerIdentityResolver identities) {
    this.ratings = ratings;
    this.identities = identities;
  }

  @PutMapping(path = "/{tacoId}/rating", consumes = "application/json")
  public Mono<RatingResponse> rate(@PathVariable("tacoId") String tacoId,
      @Valid @RequestBody RatingRequest request) {
    return identities.requiredUserId()
        .flatMap(userId -> ratings.rate(userId, tacoId, request.getScore()));
  }

  @GetMapping("/top")
  public Mono<List<TopTacoResponse>> top(
      @RequestParam(name = "limit", required = false) Integer limit) {
    return ratings.top(limit);
  }

}
