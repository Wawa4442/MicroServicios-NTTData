package tacos.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;
import tacos.Allergen;
import tacos.DietaryTag;
import tacos.SpiceLevel;
import tacos.Taco;

/**
 * {@link TacoSearchPort} on top of {@link ReactiveMongoTemplate} (TC-19).
 *
 * <p>Everything the endpoint promises happens in the database: the filters are
 * translated into one query and the page is sliced with {@code skip}/{@code
 * limit}. Nothing loads the catalog to filter it in Java, which is what keeps
 * the endpoint usable once the collection grows.
 *
 * <p>Two filter decisions are worth stating explicitly, because both are
 * visible business policy rather than plumbing:
 * <ul>
 *   <li><b>diet</b> follows the TC-17 every-policy. "Gluten free" means no
 *       ingredient breaks the rule, so the query excludes any taco holding an
 *       ingredient that does not carry the required tag. Matching
 *       {@code ingredients.dietaryTags == "VEGAN"} would have been wrong: it
 *       returns tacos that merely have one vegan ingredient among many.</li>
 *   <li><b>spice</b> is an upper bound, not an exact match. {@code spice=MEDIUM}
 *       answers "nothing hotter than medium", which is what a customer asking
 *       to cool down their order means. The levels are compared by enum
 *       ordinal, so the scale stays ordered without a lookup table.</li>
 *   <li><b>name</b> is compiled with {@link Pattern#quote} and anchored on
 *       nothing but the literal: the client cannot introduce wildcards, so
 *       there is no pattern for the engine to backtrack on, and the term length
 *       is capped on top of that.</li>
 * </ul>
 */
@Component
public class MongoTacoSearchAdapter implements TacoSearchPort {

  /**
   * Tags an ingredient may carry and still satisfy a requested diet. A VEGAN
   * ingredient is, by the TC-17 data convention, also VEGETARIAN.
   */
  private static final Map<DietaryTag, List<String>> COMPLIANT_TAGS = compliantTags();

  private final ReactiveMongoTemplate template;

  public MongoTacoSearchAdapter(ReactiveMongoTemplate template) {
    this.template = template;
  }

  @Override
  public Mono<Page<Taco>> search(TacoSearchQuery query) {
    List<Criteria> criteria = criteriaOf(query);
    Pageable pageable = query.pageable();
    Query countQuery = new Query(merge(criteria));
    Query pageQuery = new Query(merge(criteria)).with(pageable);
    return Mono.zip(
        template.count(countQuery, Taco.class),
        template.find(pageQuery, Taco.class).collectList())
        .map(tally -> new PageImpl<>(tally.getT2(), pageable, tally.getT1()));
  }

  private List<Criteria> criteriaOf(TacoSearchQuery query) {
    List<Criteria> criteria = new ArrayList<>();
    if (query.getName() != null) {
      criteria.add(Criteria.where("name").regex(Pattern.compile(
          Pattern.quote(query.getName()), Pattern.CASE_INSENSITIVE)));
    }
    if (query.getIngredientId() != null) {
      criteria.add(Criteria.where("ingredients._id").is(query.getIngredientId()));
    }
    if (query.getDiet() != null) {
      criteria.add(compliantWith(query.getDiet()));
    }
    if (query.getExcludeAllergen() != null) {
      criteria.add(freeOf(query.getExcludeAllergen()));
    }
    if (query.getSpice() != null) {
      criteria.add(notSpicierThan(query.getSpice()));
    }
    return criteria;
  }

  /**
   * "This taco is X" as "no ingredient of this taco fails to be X". A taco with
   * no dietary metadata at all fails, which keeps the answer honest instead of
   * treating missing data as a pass.
   */
  private Criteria compliantWith(DietaryTag diet) {
    return Criteria.where("ingredients").not()
        .elemMatch(Criteria.where("dietaryTags").nin(COMPLIANT_TAGS.get(diet)));
  }

  private Criteria freeOf(Allergen allergen) {
    return Criteria.where("ingredients.allergens").ne(allergen.name());
  }

  private Criteria notSpicierThan(SpiceLevel ceiling) {
    List<String> allowed = new ArrayList<>();
    for (SpiceLevel level : SpiceLevel.values()) {
      if (level.ordinal() <= ceiling.ordinal()) {
        allowed.add(level.name());
      }
    }
    return Criteria.where("ingredients.spice").in(allowed);
  }

  private Criteria merge(List<Criteria> criteria) {
    if (criteria.isEmpty()) {
      return new Criteria();
    }
    return new Criteria().andOperator(criteria);
  }

  private static Map<DietaryTag, List<String>> compliantTags() {
    Map<DietaryTag, List<String>> tags = new EnumMap<>(DietaryTag.class);
    tags.put(DietaryTag.VEGAN, Collections.singletonList("VEGAN"));
    tags.put(DietaryTag.VEGETARIAN, Arrays.asList("VEGETARIAN", "VEGAN"));
    tags.put(DietaryTag.GLUTEN_FREE, Collections.singletonList("GLUTEN_FREE"));
    return tags;
  }

}
