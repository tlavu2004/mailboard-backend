package com.awad.emailclientai.modules.kanban.repository;

import com.awad.emailclientai.modules.kanban.entity.KanbanColumn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface KanbanColumnRepository extends JpaRepository<KanbanColumn, Long> {
    
    /**
     * Find all columns for an account, ordered by position.
     */
    List<KanbanColumn> findAllByAccountIdOrderByPositionAsc(Long accountId);

    /**
     * Find a column by account and its linked status.
     */
    Optional<KanbanColumn> findByAccountIdAndLinkedStatus(Long accountId, String linkedStatus);

    /**
     * Find a column by account and Gmail label ID (case-insensitive).
     */
    Optional<KanbanColumn> findByAccountIdAndGmailLabelIdIgnoreCase(Long accountId, String gmailLabelId);
}
