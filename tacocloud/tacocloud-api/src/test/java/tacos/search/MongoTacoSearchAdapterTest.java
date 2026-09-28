package tacos.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Taco;

/**
 * TC-19: the filtering happens in MongoDB.
 *
 * <p>These tests read the {@link Query} the adapter hands to the template, and
 * that is the assertion that matters for this challenge. An implementation that
 * loaded the catalog and filtered it in Java would return the same rows for a
 * three-taco catalog while doing all the work in the heap; the query object is
 * the only place where that difference is visible, so it is what the tests read.
 */
public class MongoTacoSearchAdapterTest {

  private ReactiveMongoTemplate template;
  private MongoTacoSearchAdapter adapter;
  private final TacoSearchProperties properties = new TacoSearchProperties();

  @BeforeEach
  public void setup() {
    template = mock(ReactiveMongoTemplate.class);
    adapter = new MongoTacoSearchAdapter(template);
    when(template.count(any(Query.class), any(Class.class))).thenReturn(Mono.just(0L));
    when(template.find(any(Query.class), any(Class.class))).thenReturn(Flux.empty());
  }

  @Test
  public void tc19_unfilteredQuery_sendsNoCriteriaAndOnePage() {
    adapter.search(TacoSearchQuery.firstPage(properties)).block();

    Query page = capturedPageQuery();
    assertEquals(new Document(), page.getQueryObject(),
        "no filters means no criteria, so the database is free to use an index");
    assertEquals(0L, page.getSkip());
    assertEquals(20L, page.getLimit());
  }

  @Test
  public void tc19_ingredientFilter_isAMongoCondition() {
    adapter.search(TacoSearchQuery.of(null, "PORK", null, null, null, null, null, null,
        properties)).block();

    String json = capturedPageQuery().getQueryObject().toJson();
    assertTrue(json.contains("ingredients._id"), json);
    assertTrue(json.contains("PORK"), json);
  }

  @Test
  public void tc19_dietFilter_requiresEveryIngredientToComply() {
    adapter.search(TacoSearchQuery.of(null, null, "VEGETARIAN", null, null, null, null, null,
        properties)).block();

    String json = capturedPageQuery().getQueryObject().toJson();
    assertTrue(json.contains("$nin"), json);
    assertTrue(json.contains("VEGETARIAN"), json);
    assertTrue(json.contains("VEGAN"),
        "a vegan ingredient satisfies a vegetarian request, so it must not be listed "
            + "among the disqualifying tags");
  }

  @Test
  public void tc19_spiceFilter_isAnUpperBound() {
    adapter.search(TacoSearchQuery.of(null, null, null, null, "MILD", null, null, null,
        properties)).block();

    String json = capturedPageQuery().getQueryObject().toJson();
    assertTrue(json.contains("$in"), json);
    assertTrue(!json.contains("MEDIUM"),
        "'nothing hotter than mild' must not return medium tacos: " + json);
    assertTrue(!json.contains("HOT"), json);
  }

  @Test
  public void tc19_allergenFilter_excludes() {
    adapter.search(TacoSearchQuery.of(null, null, null, "DAIRY", null, null, null, null,
        properties)).block();

    String json = capturedPageQuery().getQueryObject().toJson();
    assertTrue(json.contains("allergens"), json);
    assertTrue(json.contains("$ne"), json);
  }

  @Test
  public void tc19_searchTerm_isQuotedSoTheClientCannotShapeThePattern() {
    adapter.search(TacoSearchQuery.of(".*", null, null, null, null, null, null, null,
        properties)).block();

    String json = capturedPageQuery().getQueryObject().toJson();
    assertTrue(json.contains("$regularExpression"), json);
    assertTrue(json.contains("\\\\Q"),
        "the term travels as a literal, not as a pattern, so there is nothing for the "
            + "engine to backtrack on: " + json);
    assertTrue(json.contains("\\\\E"), json);
  }

  @Test
  public void tc19_pageAndSort_areAppliedByTheDatabase() {
    adapter.search(TacoSearchQuery.of(null, null, null, null, null, 3, 7, "name,asc",
        properties)).block();

    Query query = capturedPageQuery();
    assertEquals(21L, query.getSkip(), "page 3 of size 7 starts at row 21");
    assertEquals(7L, query.getLimit());
    Document sort = (Document) query.getSortObject();
    assertEquals(1, sort.get("name"));
    assertEquals(1, sort.get("_id"), "the stable key is part of the sort sent to Mongo");
  }

  @Test
  public void tc19_countQuery_countsTheFilteredSetWithoutTheWindow() {
    adapter.search(TacoSearchQuery.of(null, "PORK", null, null, null, 1, 5, null, properties))
        .block();

    Query count = capturedCountQuery();
    assertEquals(0L, count.getLimit(),
        "a limited count would make totalElements and hasNext report a page that is "
            + "not the only page");
    assertEquals(0L, count.getSkip());
    assertTrue(count.getQueryObject().toJson().contains("PORK"),
        "the count must see the same filters as the page, or the total describes a "
            + "different set than the one being paged");
  }

  @Test
  public void tc19_page_reportsTheTotalAndTheRows() {
    when(template.count(any(Query.class), any(Class.class))).thenReturn(Mono.just(42L));
    when(template.find(any(Query.class), any(Class.class)))
        .thenReturn(Flux.just(taco("a"), taco("b")));

    StepVerifier.create(adapter.search(TacoSearchQuery.firstPage(properties)))
        .assertNext(page -> {
          assertEquals(42L, page.getTotalElements());
          assertEquals(3, page.getTotalPages());
          assertEquals(2, page.getContent().size());
        })
        .verifyComplete();
  }

  private static Taco taco(String id) {
    Taco taco = new Taco();
    taco.setId(id);
    taco.setName("Taco " + id);
    return taco;
  }

  private Query capturedPageQuery() {
    ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
    verify(template).find(captor.capture(), any(Class.class));
    return captor.getValue();
  }

  private Query capturedCountQuery() {
    ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
    verify(template).count(captor.capture(), any(Class.class));
    return captor.getValue();
  }

}
