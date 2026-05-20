package com.agentplatform.chat.controller;

import com.agentplatform.api.chat.MemoryFactDto;
import com.agentplatform.chat.service.MemoryService;
import com.agentplatform.security.PrincipalContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/memories")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @GetMapping
    public List<MemoryFactDto> list(@RequestParam(defaultValue = "100") int limit,
                                    @RequestParam(defaultValue = "true") boolean includeRaw) {
        return memoryService.listFacts(PrincipalContext.requireUserId(), limit, includeRaw);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        memoryService.deleteFact(PrincipalContext.requireUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
