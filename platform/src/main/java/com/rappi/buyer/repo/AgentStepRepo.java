package com.rappi.buyer.repo;

import com.rappi.buyer.domain.AgentStep;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AgentStepRepo extends JpaRepository<AgentStep, AgentStep.Key> {

    List<AgentStep> findByRunIdOrderBySeq(String runId);

    /**
     * Next sequence number for a run. Only one agent works a run at a time - the
     * redis lock sees to that - so this does not need to be atomic.
     */
    @Query("select coalesce(max(s.seq), 0) + 1 from AgentStep s where s.runId = :runId")
    int nextSeq(@Param("runId") String runId);
}
