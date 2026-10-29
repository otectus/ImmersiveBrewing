package com.otectus.immersivebrewing.api;

import java.util.List;

/** Additive API v1 catalog result. Counts describe discovered routes; incomplete/budgetLimited forbid completeness claims. */
public record CatalogPage(int generation, List<PlanQuery> plans, int knownMatches,
                          boolean incomplete, boolean budgetLimited, int omittedAlternatives) {
    public CatalogPage { plans = List.copyOf(plans); }
}
