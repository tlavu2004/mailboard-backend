package com.awad.emailclientai.modules.kanban.controller;

import com.awad.emailclientai.modules.kanban.entity.KanbanColumn;
import com.awad.emailclientai.modules.kanban.service.KanbanService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/kanban/columns")
@RequiredArgsConstructor
public class KanbanController {

    private final KanbanService kanbanService;

    @GetMapping
    public ResponseEntity<List<KanbanColumn>> getColumns(@RequestParam("accountId") Long accountId) {
        return ResponseEntity.ok(kanbanService.getColumns(accountId));
    }

    @PostMapping
    public ResponseEntity<KanbanColumn> createColumn(@RequestBody CreateColumnRequest request) {
        return ResponseEntity.ok(kanbanService.createColumn(request.getAccountId(), request.getName()));
    }

    @PutMapping("/{id}")
    public ResponseEntity<KanbanColumn> updateColumn(@PathVariable Long id, @RequestBody UpdateColumnRequest request) {
        return ResponseEntity.ok(kanbanService.updateColumn(id, request.getName(), request.getPosition()));
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
    }

    @Data
    public static class UpdateColumnRequest {
        private String name;
        private Integer position;
    }
    
    @Data
    public static class SwapRequest {
        private Long accountId;
        private Long columnId1;
        private Long columnId2;
    }
}
