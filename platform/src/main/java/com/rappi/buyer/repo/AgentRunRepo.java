package com.rappi.buyer.repo;

import com.rappi.buyer.domain.AgentRun;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AgentRunRepo extends JpaRepository<AgentRun, String> {

    List<AgentRun> findTop50ByOrderByCreatedAtDesc();
}
