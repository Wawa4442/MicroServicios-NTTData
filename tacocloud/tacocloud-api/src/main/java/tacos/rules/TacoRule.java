package tacos.rules;

import java.util.List;

import tacos.Ingredient;

/**
 * One composable design rule (TC-18). A rule inspects a list of resolved
 * ingredients and returns every violation it can prove, or an empty list.
 *
 * <p>Rules are single-responsibility classes registered as Spring beans; the
 * validator simply runs the injected collection, so a new rule can be added
 * without touching the central validator. Rule order does not change the
 * outcome: all violations are collected, there is no fail-fast.
 */
public interface TacoRule {

  List<RuleViolation> check(List<Ingredient> ingredients);

}