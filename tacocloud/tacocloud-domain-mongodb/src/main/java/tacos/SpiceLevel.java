package tacos;

/**
 * Deterministic heat scale. The spice level of a taco is the maximum of its
 * ingredients' levels, so the policy is reproducible and explainable.
 */
public enum SpiceLevel {
  NONE, MILD, MEDIUM, HOT, EXTRA_HOT;

  public SpiceLevel max(SpiceLevel other) {
    return this.ordinal() >= other.ordinal() ? this : other;
  }
}