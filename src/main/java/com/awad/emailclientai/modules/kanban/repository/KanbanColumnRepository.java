package com.awad.emailclientai.modules.kanban.repository;

import com.awad.emailclientai.modules.kanban.entity.KanbanColumn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KanbanColumnRepository extends JpaRepository<KanbanColumn, Long> {
    
    /**
     * Find all columns for an account, ordered by position.
     */
    List<KanbanColumn> findAllByAccountIdOrderByPositionAsc(Long accountId);
}
