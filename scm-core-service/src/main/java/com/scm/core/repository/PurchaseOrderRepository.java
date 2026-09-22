package com.scm.core.repository;

import com.scm.core.domain.PurchaseOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    Optional<PurchaseOrder> findByOrderNumber(String orderNumber);

    List<PurchaseOrder> findAllByOrderByCreatedAtDesc();

    long countByStatusIn(List<PurchaseOrder.Status> statuses);
}
