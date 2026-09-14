package com.rappi.buyer.constraints;

import java.util.List;

/**
 * What came back from the constraint engine. The agent gets this verbatim, and
 * so does the approval queue - a buyer should be able to see exactly which rule
 * made them the decision maker.
 */
public record ValidationReport(
        Verdict verdict,
        String riskTier,
        List<Check> checks,
        List<String> blocking,
        boolean requiresApproval) {

    public enum Verdict { PASS, PASS_WITH_WARNINGS, NEEDS_APPROVAL, BLOCKED }

    public enum Status { PASS, WARN, APPROVAL, BLOCK }

    public record Check(String id, Status status, String detail) {}

    public boolean ok() {
        return verdict == Verdict.PASS || verdict == Verdict.PASS_WITH_WARNINGS;
    }

    public List<Check> failures() {
        return checks.stream().filter(c -> c.status() != Status.PASS).toList();
    }
}
