package tacos.web.api;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.recommendation.TacoOfTheDay;
import tacos.recommendation.TacoOfTheDayService;

/**
 * {@code GET /api/tacos/today} (TC-20). The literal path wins over
 * {@code /api/tacos/{id}}, so this never has to be mistaken for a taco whose
 * id happens to be the word "today".
 *
 * <p>Reads nothing, writes nothing: the same date always answers with the same
 * taco, and asking twice does not create anything.
 */
@RestController
@RequestMapping(path = "/api/tacos", produces = "application/json")
@CrossOrigin(origins = "http://localhost:8080")
public class TacoOfTheDayController {

  private final TacoOfTheDayService service;

  public TacoOfTheDayController(TacoOfTheDayService service) {
    this.service = service;
  }

  @GetMapping("/today")
  public Mono<TacoOfTheDay> tacoOfTheDay() {
    return service.today();
  }

}
