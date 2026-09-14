package com.rappi.buyer.verify;

import com.rappi.buyer.constraints.Proposal;
import com.rappi.buyer.tools.PurchaseOrderService;
import com.rappi.buyer.tools.PurchaseOrderService.WriteResult;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The case the whole feedback loop exists for: the agent placed an order, the
 * supplier could not fill it, and nobody told the agent.
 *
 * app.faults.supplier-cap forces the shortfall so this runs against a quantity
 * that is small enough to be auto approved. Left alone the mock still caps
 * naturally - SUP-ANDINA can only ship 250 coffee - but 500 coffee is $1950,
 * which the tier gate sends to a human before it ever reaches the supplier.
 * Both behaviours are correct; this test needs the second one out of the way.
 */
@SpringBootTest
@TestPropertySource(properties = "app.faults.supplier-cap=120")
class SupplierShortfallIT {

    @Autowired PurchaseOrderService orders;
    @Autowired Verifier verifier;

    static final String SKU = "SKU-CHIPS-150G";
    static final String NODE = "NODE-BOG-01";
    static final String SUP = "SUP-SNACKCO";

    @Test
    @DisplayName("ordered 240, supplier shipped 120, and the agent works that out for itself")
    void shortfallIsCaughtByVerification() {
        LocalDate delivery = LocalDate.of(2026, 3, 26);
        BigDecimal price = new BigDecimal("0.62");

        WriteResult res = orders.create(SKU, NODE, SUP, 240, price, delivery,
                "shortfall-" + UUID.randomUUID(), 240, null, "test");

        assertThat(res.executed())
                .as("240 chips at $148.80 should be inside the agent's authority")
                .isTrue();

        Proposal intended = new Proposal(SKU, NODE, SUP, 240, price, delivery, 240);
        VerificationReport report = verifier.verify(res.poId(), intended);

        System.out.println();
        System.out.println("VERIFICATION            INTENDED      ACTUAL       VERDICT");
        for (VerificationReport.Diff d : report.diffs()) {
            System.out.printf("%-3s %-19s %-13s %-12s %s%n",
                    d.level(), d.field(), d.intended(), d.actual(),
                    d.ok() ? "ok" : "MISMATCH");
        }
        report.notes().forEach(n -> System.out.println("    " + n));
        System.out.println("RESULT  " + report.outcome());
        System.out.println();

        // L1 caught it: we asked for 240 and the database holds 120 confirmed
        assertThat(report.outcome()).isEqualTo(VerificationReport.Outcome.MISMATCH);
        assertThat(report.mismatches())
                .anyMatch(d -> d.level().equals("L1")
                        && d.field().equals("qtyConfirmed")
                        && d.actual().equals("120"));

        // and the write itself reported the shortfall rather than claiming success
        assertThat(res.message()).contains("120 of 240");

        orders.cancel(res.poId(), orders.reread(res.poId()).getVersion(), "cleanup");
    }
}
