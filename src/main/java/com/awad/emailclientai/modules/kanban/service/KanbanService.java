package com.awad.emailclientai.modules.kanban.service;

import com.awad.emailclientai.modules.email.entity.EmailAccount;
import com.awad.emailclientai.modules.email.repository.EmailAccountRepository;
import com.awad.emailclientai.modules.kanban.entity.KanbanColumn;
import com.awad.emailclientai.modules.kanban.repository.KanbanColumnRepository;
import com.awad.emailclientai.shared.exception.BusinessException;
import com.awad.emailclientai.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class KanbanService {
    
    private final KanbanColumnRepository kanbanColumnRepository;
    private final EmailAccountRepository emailAccountRepository;

    // Default columns that must always exist for every account
    private static final List<String[]> DEFAULT_COLUMNS = List.of(
        new String[]{"Inbox", "INBOX"},
        new String[]{"To Do", "TODO"},
        new String[]{"In Progress", "IN_PROGRESS"},
        new String[]{"Done", "DONE"},
        new String[]{"Snoozed", "SNOOZED"}
    );

    // Set of default statuses that cannot be modified or deleted
    private static final Set<String> DEFAULT_STATUSES = Set.of(
        "INBOX", "TODO", "IN_PROGRESS", "DONE", "SNOOZED"
    );

    private boolean isDefaultColumn(KanbanColumn column) {
        return column.getLinkedStatus() != null && DEFAULT_STATUSES.contains(column.getLinkedStatus());
    }

    @Transactional
    public List<KanbanColumn> getColumns(Long accountId) {
        List<KanbanColumn> columns = kanbanColumnRepository.findAllByAccountIdOrderByPositionAsc(accountId);
        
        // Check which default statuses are missing
        Set<String> existingStatuses = columns.stream()
                .map(KanbanColumn::getLinkedStatus)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<String[]> missingDefaults = DEFAULT_COLUMNS.stream()
                .filter(d -> !existingStatuses.contains(d[1]))
                .collect(Collectors.toList());

        if (!missingDefaults.isEmpty()) {
            columns = ensureDefaultColumns(accountId, columns, missingDefaults);
        }

        return columns;
    }

    private List<KanbanColumn> ensureDefaultColumns(Long accountId, List<KanbanColumn> existing, List<String[]> missing) {
        EmailAccount account = emailAccountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_ACCOUNT_NOT_FOUND));

        int maxPos = existing.stream()
                .mapToInt(KanbanColumn::getPosition)
                .max()
                .orElse(-1);

        List<KanbanColumn> newColumns = new ArrayList<>();
        for (String[] def : missing) {
            maxPos++;
            newColumns.add(KanbanColumn.builder()
                    .name(def[0])
                    .linkedStatus(def[1])
                    .position(maxPos)
                    .account(account)
                    .build());
        }

        kanbanColumnRepository.saveAll(newColumns);

        // Return full sorted list
        return kanbanColumnRepository.findAllByAccountIdOrderByPositionAsc(accountId);
    }

    @Transactional
    public KanbanColumn createColumn(Long accountId, String name) {
        // ... existing logic ...
        EmailAccount account = emailAccountRepository.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_ACCOUNT_NOT_FOUND));
        
        // Find max position
        int maxPos = kanbanColumnRepository.findAllByAccountIdOrderByPositionAsc(accountId)
                .stream()
                .mapToInt(KanbanColumn::getPosition)
                .max()
                .orElse(-1);

        KanbanColumn column = KanbanColumn.builder()
                .name(name)
                .linkedStatus(name.toUpperCase().replace(" ", "_")) // Default linked status for now
                .account(account)
                .position(maxPos + 1)
                .build();
        
        return kanbanColumnRepository.save(column);
    }

    @Transactional
    public KanbanColumn updateColumn(Long id, String name, Integer position) {
        KanbanColumn column = kanbanColumnRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.KANBAN_COLUMN_NOT_FOUND));

        // Protect default columns from being renamed
        if (isDefaultColumn(column) && name != null && !name.isBlank()) {
            throw new BusinessException(ErrorCode.KANBAN_CANNOT_MODIFY_DEFAULT, "Cannot rename default column: " + column.getName());
        }
        
        if (name != null && !name.isBlank()) {
            column.setName(name);
        }
        
        if (position != null) {
            column.setPosition(position);
        }
        
        return kanbanColumnRepository.save(column);
    }

    @Transactional
    public void deleteColumn(Long id) {
        KanbanColumn column = kanbanColumnRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.KANBAN_COLUMN_NOT_FOUND));

        // Protect default columns from being deleted
        if (isDefaultColumn(column)) {
            throw new BusinessException(ErrorCode.KANBAN_CANNOT_DELETE_DEFAULT, "Cannot delete default column: " + column.getName());
        }
        kanbanColumnRepository.deleteById(id);
    }

    @Transactional
    public List<KanbanColumn> swapColumns(Long accountId, Long columnId1, Long columnId2) {
        if (columnId1.equals(columnId2)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Cannot swap a column with itself");
        }

        List<KanbanColumn> columns = kanbanColumnRepository.findAllByAccountIdOrderByPositionAsc(accountId);

        KanbanColumn col1 = columns.stream()
                .filter(c -> c.getId().equals(columnId1))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.KANBAN_COLUMN_NOT_FOUND, "Column not found: " + columnId1));

        KanbanColumn col2 = columns.stream()
                .filter(c -> c.getId().equals(columnId2))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.KANBAN_COLUMN_NOT_FOUND, "Column not found: " + columnId2));

        // Swap positions
        int tempPos = col1.getPosition();
        col1.setPosition(col2.getPosition());
        col2.setPosition(tempPos);

        kanbanColumnRepository.save(col1);
        kanbanColumnRepository.save(col2);

        return kanbanColumnRepository.findAllByAccountIdOrderByPositionAsc(accountId);
    }
}
