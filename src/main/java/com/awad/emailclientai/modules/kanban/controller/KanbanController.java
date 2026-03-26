package com.awad.emailclientai.modules.kanban.controller;

import com.awad.emailclientai.modules.email.repository.EmailAccountRepository;
import com.awad.emailclientai.modules.kanban.entity.KanbanColumn;
import com.awad.emailclientai.modules.kanban.service.KanbanService;
import com.awad.emailclientai.modules.user.security.UserPrincipal;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/kanban/columns")
@RequiredArgsConstructor
public class KanbanController {

    private final KanbanService kanbanService;
    private final EmailAccountRepository emailAccountRepository;

    @GetMapping
    public ResponseEntity<List<KanbanColumn>> getColumns(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(value = "accountId", required = false) Long accountId
    ) {
        if (accountId == null) {
            accountId = emailAccountRepository.findByUserIdAndActiveTrue(principal.getId())
                    .stream().findFirst()
                    .orElseThrow(() -> new RuntimeException("No active email account found"))
                    .getId();
        }
        return ResponseEntity.ok(kanbanService.getColumns(accountId));
    }

    @PostMapping
    public ResponseEntity<KanbanColumn> createColumn(@RequestBody CreateColumnRequest request) {
        return ResponseEntity.ok(kanbanService.createColumn(request.getAccountId(), request.getName(), request.getGmailLabelId()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<KanbanColumn> updateColumn(@PathVariable Long id, @RequestBody UpdateColumnRequest request) {
        return ResponseEntity.ok(kanbanService.updateColumn(id, request.getName(), request.getPosition(), request.getGmailLabelId()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteColumn(@PathVariable Long id) {
        kanbanService.deleteColumn(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/swap")
    public ResponseEntity<List<KanbanColumn>> swapColumns(@RequestBody SwapRequest request) {
        return ResponseEntity.ok(kanbanService.swapColumns(request.getAccountId(), request.getColumnId1(), request.getColumnId2()));
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
