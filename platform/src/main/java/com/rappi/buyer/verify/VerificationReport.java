package com.rappi.buyer.verify;

import java.util.List;

/**
 * What came back from checking our own work.
 *
 * The diff list is the point - it renders straight into the ui as
 * intended vs actual, so a mismatch is something you can see rather than
 * something buried in a log.
 */
public record VerificationReport(
        String poId,
        Outcome outcome,
        List<Diff> diffs,
        List<String> notes) {

    public enum Outcome { VERIFIED, MISMATCH, UNRECOVERABLE }

    /** level is L1, L2 or L3. */
    public record Diff(String level, String field, String intended, String actual, boolean ok) {}

    public List<Diff> mismatches() {
        return diffs.stream().filter(d -> !d.ok()).toList();
    }

    public boolean verified() {
        return outcome == Outcome.VERIFIED;
    }
}
