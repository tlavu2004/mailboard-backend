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
        if (accountId == null) {
            accountId = emailAccountRepository.findByUserIdAndActiveTrue(principal.getId())
                    .stream().findFirst()
                    .orElseThrow(() -> new RuntimeException("No active email account found"))
                    .getId();
        }
        
        List<KanbanColumn> columns = kanbanService.getColumns(accountId);
        
        // Map to frontend format
        List<Map<String, Object>> mappedColumns = columns.stream()
                .map(col -> {
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", col.getId().toString());
                    map.put("key", col.getLinkedStatus());
                    map.put("label", col.getName());
                    map.put("order", col.getPosition());
                    map.put("gmailLabel", col.getGmailLabelId());
                    map.put("color", "#667eea"); // Default color
                    map.put("isDefault", col.getLinkedStatus() != null); 
                    return map;
                })
                .collect(Collectors.toList());

        Map<String, Object> data = new HashMap<>();
        data.put("columns", mappedColumns);
        
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> createColumn(@RequestBody CreateColumnRequest request) {
        KanbanColumn col = kanbanService.createColumn(request.getAccountId(), request.getName(), request.getGmailLabelId());
        return ResponseEntity.ok(ApiResponse.success(Map.of("column", col)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateColumn(@PathVariable Long id, @RequestBody UpdateColumnRequest request) {
        KanbanColumn col = kanbanService.updateColumn(id, request.getName(), request.getPosition(), request.getGmailLabelId());
        return ResponseEntity.ok(ApiResponse.success(Map.of("column", col)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteColumn(@PathVariable Long id) {
        kanbanService.deleteColumn(id);
        return ResponseEntity.ok(ApiResponse.success("Column deleted"));
    }

    @PostMapping("/swap")
    public ResponseEntity<ApiResponse<Map<String, Object>>> swapColumns(@RequestBody SwapRequest request) {
        List<KanbanColumn> columns = kanbanService.swapColumns(request.getAccountId(), request.getColumnId1(), request.getColumnId2());
        return ResponseEntity.ok(ApiResponse.success(Map.of("columns", columns)));
    }

    @PostMapping("/reorder")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reorderColumns(@RequestBody Map<String, Object> request) {
        // Just a bridge for now, reorder logic can be complex, but for consistency:
        return ResponseEntity.ok(ApiResponse.success("Reorder received"));
    }

    @Data
    public static class CreateColumnRequest {
        private Long accountId;
        private String name;
        private String gmailLabelId;
    }

    @Data
    public static class UpdateColumnRequest {
        private String name;
        private Integer position;
        private String gmailLabelId;
    }
    
    @Data
    public static class SwapRequest {
        private Long accountId;
        private Long columnId1;
        private Long columnId2;
    }
}
