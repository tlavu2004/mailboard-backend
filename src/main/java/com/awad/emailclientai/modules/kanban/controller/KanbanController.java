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
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/kanban/columns")
@RequiredArgsConstructor
public class KanbanController {

    private final KanbanService kanbanService;
    private final EmailAccountRepository emailAccountRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getColumns(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "accountId", required = false) Long accountId
    ) {
        Long finalAccountId = resolveAccountId(principal, accountId);
        List<KanbanColumn> columns = kanbanService.getColumns(finalAccountId);
        
        // Map to frontend format
        List<Map<String, Object>> mappedColumns = columns.stream()
                .map(col -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", col.getId().toString());
                    map.put("key", col.getLinkedStatus());
                    map.put("label", col.getName());
                    map.put("order", col.getPosition());
                    map.put("gmailLabel", col.getGmailLabelId());
                    map.put("color", "#f1f5f9"); // Default slate-100 color
                    map.put("isDefault", col.getLinkedStatus() != null); 
                    return map;
                })
                .collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("columns", mappedColumns);
        
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> createColumn(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody CreateColumnRequest request
    ) {
        Long accountId = resolveAccountId(principal, request.getAccountId());
        // Handle alias from frontend (label -> name)
        String name = request.getLabel() != null ? request.getLabel() : request.getName();
        String gmailLabelId = request.getGmailLabel() != null ? request.getGmailLabel() : request.getGmailLabelId();
        
        KanbanColumn col = kanbanService.createColumn(accountId, name, gmailLabelId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("column", col)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateColumn(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long id, 
            @RequestBody UpdateColumnRequest request
    ) {
        // Handle alias from frontend (label -> name)
        String name = request.getLabel() != null ? request.getLabel() : request.getName();
        String gmailLabelId = request.getGmailLabel() != null ? request.getGmailLabel() : request.getGmailLabelId();
        
        KanbanColumn col = kanbanService.updateColumn(id, name, request.getPosition(), gmailLabelId);
        return ResponseEntity.ok(ApiResponse.success(Map.of("column", col)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteColumn(@PathVariable Long id) {
        kanbanService.deleteColumn(id);
        return ResponseEntity.ok(ApiResponse.success("Column deleted"));
    }

    @PostMapping("/swap")
    public ResponseEntity<ApiResponse<Map<String, Object>>> swapColumns(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody SwapRequest request
    ) {
        Long accountId = resolveAccountId(principal, request.getAccountId());
        List<KanbanColumn> columns = kanbanService.swapColumns(accountId, request.getColumnId1(), request.getColumnId2());
        return ResponseEntity.ok(ApiResponse.success(Map.of("columns", columns)));
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
        return ResponseEntity.ok(ApiResponse.success(Map.of("columns", columns)));
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
        private String gmailLabel; // Alias from frontend
    }

    @Data
    public static class UpdateColumnRequest {
        private String name;
        private String label; // Alias from frontend
        private Integer position;
        private String gmailLabelId;
        private String gmailLabel; // Alias from frontend
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
