package tacos.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import tacos.Allergen;
import tacos.DietaryTag;
import tacos.SpiceLevel;
import tacos.paging.InvalidPageBoundsException;

/**
 * TC-19: the request is turned into a query that is safe, bounded and stable
 * before anything reaches the database.
 *
 * <p>Each case here is a rejection the endpoint owes the client, and the reason
 * it is tested on the query rather than on the controller is that the same
 * factory is the only door to the adapter: if a value cannot become an unsafe
 * query here, the adapter never has to defend against it again.
 */
public class TacoSearchQueryTest {

  private final TacoSearchProperties properties = new TacoSearchProperties();

  @Test
  public void tc19_emptyRequest_isAValidQueryOrderedByNewestFirst() {
    TacoSearchQuery query = TacoSearchQuery.firstPage(properties);

    assertTrue(query.isUnfiltered(), "an absent filter means no filter, not a search term");
    assertNull(query.getName());
    assertNull(query.getIngredientId());
    assertNull(query.getDiet());
    assertNull(query.getExcludeAllergen());
    assertNull(query.getSpice());
    assertEquals(0, query.getPage().getPage());
    assertEquals(20, query.getPage().getSize());
    assertEquals("createdAt", query.getSort().getField());
    assertTrue(query.getSort().isDescending());
  }

  @Test
  public void tc19_filtersAreParsedIntoEnums() {
    TacoSearchQuery query = TacoSearchQuery.of("  al pastor ", "PORK", "vegan",
        "gluten", "medium", null, null, null, properties);

    assertEquals("al pastor", query.getName());
    assertEquals("PORK", query.getIngredientId());
    assertEquals(DietaryTag.VEGAN, query.getDiet());
    assertEquals(Allergen.GLUTEN, query.getExcludeAllergen());
    assertEquals(SpiceLevel.MEDIUM, query.getSpice());
    assertTrue(!query.isUnfiltered());
  }

  @Test
  public void tc19_blankParametersAreTreatedAsAbsent() {
    TacoSearchQuery query = TacoSearchQuery.of("   ", "", "  ", null, "\t", null, null, null,
        properties);

    assertTrue(query.isUnfiltered(),
        "a whitespace-only parameter is not a filter; answering 400 for a stray "
            + "space would be noise");
  }

  @Test
  public void tc19_unknownEnumValue_isRejectedWithTheAllowedOnes() {
    InvalidTacoSearchException failure = assertThrows(InvalidTacoSearchException.class,
        () -> TacoSearchQuery.of(null, null, "carnivore", null, null, null, null, null,
            properties));

    assertTrue(failure.getMessage().contains("diet"), "the message names the offending field");
    assertTrue(failure.getMessage().contains("VEGAN"),
        "a rejected value must come back with the values that would work");
  }

  @Test
  public void tc19_sortFieldOutsideTheWhitelist_isRejected() {
    InvalidTacoSearchException failure = assertThrows(InvalidTacoSearchException.class,
        () -> TacoSearchQuery.of(null, null, null, null, null, null, null, "price,asc",
            properties));

    assertTrue(failure.getMessage().contains("createdAt"),
        "the message lists the sortable fields");
  }

  @Test
  public void tc19_unknownSortDirection_isRejected() {
    assertThrows(InvalidTacoSearchException.class,
        () -> TacoSearchQuery.of(null, null, null, null, null, null, null, "name,sideways",
            properties));
  }

  @Test
  public void tc19_pageWindow_isBounded() {
    assertThrows(InvalidPageBoundsException.class,
        () -> TacoSearchQuery.of(null, null, null, null, null, 0, 0, null, properties),
        "size=0 would ask for an empty page forever");
    assertThrows(InvalidPageBoundsException.class,
        () -> TacoSearchQuery.of(null, null, null, null, null, 0, 101, null, properties));
    assertThrows(InvalidPageBoundsException.class,
        () -> TacoSearchQuery.of(null, null, null, null, null, -1, null, null, properties));
  }

  @Test
  public void tc19_deepPaging_isRefusedInsteadOfScanningTheCatalog() {
    InvalidPageBoundsException failure = assertThrows(InvalidPageBoundsException.class,
        () -> TacoSearchQuery.of(null, null, null, null, null, 10_000, 20, null, properties));

    assertTrue(failure.getMessage().contains("offset"),
        "the message explains that the window, not the data, was the problem");
  }

  @Test
  public void tc19_oversizedSearchTerm_isRejected() {
    String tooLong = "a".repeat(properties.getMaxTextLength() + 1);

    assertThrows(InvalidTacoSearchException.class,
        () -> TacoSearchQuery.of(tooLong, null, null, null, null, null, null, null, properties));
  }

  @Test
  public void tc19_pageable_appendsTheStableTieBreaker() {
    Pageable newestFirst = TacoSearchQuery.firstPage(properties).pageable();
    assertEquals(Sort.Order.desc("createdAt"), newestFirst.getSort().getOrderFor("createdAt"));
    assertEquals(Sort.Order.desc("_id"), newestFirst.getSort().getOrderFor("_id"),
        "without the trailing key two tacos created in the same millisecond could "
            + "appear on two pages or on none");

    Pageable byName = TacoSearchQuery.of(null, null, null, null, null, null, null, "name,asc",
        properties).pageable();
    assertEquals(Sort.Order.asc("name"), byName.getSort().getOrderFor("name"));
    assertEquals(Sort.Order.asc("_id"), byName.getSort().getOrderFor("_id"),
        "the tie-breaker follows the direction of the primary key so the order "
            + "is still a total order");
  }

  @Test
  public void tc19_pageable_carriesTheWindow() {
    Pageable secondPage = TacoSearchQuery.of(null, null, null, null, null, 2, 5, null, properties)
        .pageable();

    assertEquals(2, secondPage.getPageNumber());
    assertEquals(5, secondPage.getPageSize());
    assertEquals(10, secondPage.getOffset());
  }

}
