package com.fintech.shared.selfcheck;

import java.time.Instant;
import java.util.List;

/**
 * What a service found when it checked itself: every check it ran and the outcome. In order when every check that must
 * hold holds; the informative ones are reported only.
 *
 * <p>The system verifies itself: a run's verdict reads these reports instead of recomputing the facts from outside, and
 * an external audit (the chain's cross-schema conciliation) stays a second, independent opinion.
 *
 * @param scope what was checked: a period, or {@code "*"} for everything the service holds
 */
public record SelfCheckReport(String service, String scope, String outcome, Instant runAt, List<SelfCheck> checks) {

    public static final String IN_ORDER = "IN_ORDER";
    public static final String DIFFERENCES = "DIFFERENCES";
    public static final String EVERYTHING = "*";

    public static SelfCheckReport of(String service, String scope, Instant runAt, List<SelfCheck> checks) {
        boolean ok = checks.stream().noneMatch(c -> c.blocking() && !c.ok());
        return new SelfCheckReport(service, scope == null || scope.isBlank() ? EVERYTHING : scope,
                ok ? IN_ORDER : DIFFERENCES, runAt, List.copyOf(checks));
    }

    public boolean inOrder() {
        return IN_ORDER.equals(outcome);
    }

    /** The checks that must hold and do not. */
    public List<SelfCheck> failures() {
        return checks.stream().filter(c -> c.blocking() && !c.ok()).toList();
    }
}
