package com.rappi.buyer.api;

import com.rappi.buyer.domain.PlanningException;
import com.rappi.buyer.planner.BatchPlanner;
import com.rappi.buyer.repo.PlanningExceptionRepo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Run the nightly batch by hand, and read what the last one found. */
@RestController
public class PlanningController {

    private final BatchPlanner batch;
    private final PlanningExceptionRepo exceptions;

    public PlanningController(BatchPlanner batch, PlanningExceptionRepo exceptions) {
        this.batch = batch;
        this.exceptions = exceptions;
    }

    @PostMapping("/planning/batch")
    public BatchPlanner.Summary run() {
        return batch.run();
    }

    @GetMapping("/planning/exceptions")
    public List<PlanningException> latest() {
        return exceptions.findTopByOrderByIdDesc()
                .map(e -> exceptions.findByBatchIdOrderById(e.getBatchId()))
                .orElse(List.of());
    }
}
