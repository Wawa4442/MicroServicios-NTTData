package tacos;

/**
 * Allergens present in an ingredient. The allergens of a taco are the exact
 * union of its ingredients' allergens; they are never removed by majority.
 */
public enum Allergen {
  GLUTEN, DAIRY, MEAT, NUTS, SOY, EGG
}