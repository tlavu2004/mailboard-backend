package com.awad.emailclientai.modules.kanban.controller;
import com.awad.emailclientai.modules.email.repository.EmailAccountRepository;
import com.awad.emailclientai.modules.kanban.entity.KanbanColumn;
import com.awad.emailclientai.modules.kanban.service.KanbanService;
import com.awad.emailclientai.modules.user.security.UserPrincipal;
import com.awad.emailclientai.shared.dto.response.ApiResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/kanban/columns")
@RequiredArgsConstructor
public class KanbanController {

    private final KanbanService kanbanService;
    private final EmailAccountRepository emailAccountRepository;

    // Must match service's DEFAULT_STATUSES
    private static final Set<String> SYSTEM_DEFAULT_STATUSES = Set.of(
        "INBOX", "TODO", "IN_PROGRESS", "DONE", "SNOOZED"
    );

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getColumns(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "accountId", required = false) Long accountId
    ) {
        Long finalAccountId = resolveAccountId(principal, accountId);
        List<KanbanColumn> columns = kanbanService.getColumns(finalAccountId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("columns", mapToDtoList(columns))));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> createColumn(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody CreateColumnRequest request
    ) {
        Long accountId = resolveAccountId(principal, request.getAccountId());
        String name = request.getLabel() != null ? request.getLabel() : request.getName();
        String gmailLabelId = request.getGmailLabel() != null ? request.getGmailLabel() : request.getGmailLabelId();
        
        // Return updated list after creation to ensure sync
        kanbanService.createColumn(accountId, name, gmailLabelId, request.getColor());
        List<KanbanColumn> updated = kanbanService.getColumns(accountId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("columns", mapToDtoList(updated))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateColumn(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id, 
            @RequestBody UpdateColumnRequest request
    ) {
        String name = request.getLabel() != null ? request.getLabel() : request.getName();
        String gmailLabelId = request.getGmailLabel() != null ? request.getGmailLabel() : request.getGmailLabelId();
        
        KanbanColumn col = kanbanService.updateColumn(id, name, request.getPosition(), gmailLabelId, request.getColor());
        // For update, we can return the updated list or just the column.
        // Returning the list is safer for sorting synchronization.
        Long accountId = col.getAccount().getId();
        List<KanbanColumn> updated = kanbanService.getColumns(accountId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("columns", mapToDtoList(updated))));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> deleteColumn(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id,
            @RequestParam(value = "accountId", required = false) Long accountId
    ) {
        Long finalAccountId = resolveAccountId(principal, accountId);
        List<KanbanColumn> updated = kanbanService.deleteColumn(id, finalAccountId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("columns", mapToDtoList(updated))));
    }

    @PostMapping("/swap")
    public ResponseEntity<ApiResponse<Map<String, Object>>> swapColumns(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody SwapRequest request
    ) {
        Long accountId = resolveAccountId(principal, request.getAccountId());
        List<KanbanColumn> columns = kanbanService.swapColumns(accountId, request.getColumnId1(), request.getColumnId2());
        return ResponseEntity.ok(ApiResponse.success(Map.of("columns", mapToDtoList(columns))));
    }

    @PostMapping("/reorder")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reorderColumns(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody ReorderRequest request
    ) {
        Long accountId = resolveAccountId(principal, request.getAccountId());
        List<Long> ids = request.getColumnIds();
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.badRequest().body(ApiResponse.error("List of IDs is required"));
        }
        List<KanbanColumn> columns = kanbanService.reorderColumns(accountId, ids);
        return ResponseEntity.ok(ApiResponse.success(Map.of("columns", mapToDtoList(columns))));
    }

    private List<Map<String, Object>> mapToDtoList(List<KanbanColumn> columns) {
        return columns.stream()
                .map(col -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", col.getId().toString());
                    // Key fallback: if linkedStatus is null (new user-created columns), use ID as key
                    map.put("key", col.getLinkedStatus() != null ? col.getLinkedStatus() : col.getId().toString());
                    map.put("label", col.getName());
                    map.put("order", col.getPosition());
                    map.put("gmailLabel", col.getGmailLabelId());
                    map.put("color", col.getColor() != null ? col.getColor() : "#f1f5f9"); 
                    // isDefault only for true system statuses
                    map.put("isDefault", col.getLinkedStatus() != null && SYSTEM_DEFAULT_STATUSES.contains(col.getLinkedStatus())); 
                    return map;
                })
                .collect(Collectors.toList());
    }

    private Long resolveAccountId(UserPrincipal principal, Long requestedAccountId) {
        if (requestedAccountId != null) return requestedAccountId;
        return emailAccountRepository.findByUserIdAndActiveTrue(principal.getId())
                .stream().findFirst()
                .orElseThrow(() -> new RuntimeException("No active email account found"))
                .getId();
    }

    @Data
    public static class CreateColumnRequest {
        private Long accountId;
        private String name;
        private String label; // Alias from frontend
        private String gmailLabelId;
        private String gmailLabel; 
        private String color;
    }

    @Data
    public static class UpdateColumnRequest {
        private String name;
        private String label; 
        private Integer position;
        private String gmailLabelId;
        private String gmailLabel; 
        private String color;
    }

    @Data
    public static class ReorderRequest {
        private Long accountId;
        private List<Long> columnIds;
    }
    
    @Data
    public static class SwapRequest {
        private Long accountId;
        private Long columnId1;
        private Long columnId2;
    }
}
