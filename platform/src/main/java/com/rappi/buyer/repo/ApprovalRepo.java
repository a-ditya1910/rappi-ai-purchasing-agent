package com.rappi.buyer.repo;

import com.rappi.buyer.domain.Approval;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApprovalRepo extends JpaRepository<Approval, String> {

    List<Approval> findByStatusOrderByCreatedAtDesc(Approval.Status status);

    List<Approval> findTop50ByOrderByCreatedAtDesc();
}
