package com.agentplatform.chat.controller;

import com.agentplatform.api.chat.MessageDto;
import com.agentplatform.api.chat.SessionDto;
import com.agentplatform.chat.service.MessageService;
import com.agentplatform.chat.service.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentplatform.security.PrincipalContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final MessageService messageService;
    private final ObjectMapper mapper;

    public SessionController(SessionService sessionService, MessageService messageService, ObjectMapper mapper) {
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.mapper = mapper;
    }

    @GetMapping
    public List<SessionDto> list() {
        return sessionService.listByUser(PrincipalContext.requireUserId());
    }

    @PostMapping
    public ResponseEntity<SessionDto> create(@RequestBody(required = false) CreateSessionBody body) {
        UUID userId = PrincipalContext.requireUserId();
        String title = body != null ? body.title() : null;
        return ResponseEntity.status(HttpStatus.CREATED).body(sessionService.create(userId, title));
    }

    @GetMapping("/{id}")
    public SessionDto get(@PathVariable UUID id) {
        return sessionService.get(id, PrincipalContext.requireUserId());
    }

    @GetMapping("/{id}/messages")
    public List<MessageDto> messages(@PathVariable UUID id) {
        return messageService.listBySession(id, PrincipalContext.requireUserId());
    }

    @GetMapping(value = "/{id}/export.jsonl", produces = "application/x-ndjson")
    public ResponseEntity<String> exportJsonl(@PathVariable UUID id) throws Exception {
        UUID userId = PrincipalContext.requireUserId();
        SessionDto session = sessionService.get(id, userId);
        List<MessageDto> rows = messageService.listBySession(id, userId);
        StringBuilder out = new StringBuilder();
        LinkedHashMap<String, Object> sessionLine = new LinkedHashMap<>();
        sessionLine.put("type", "session");
        sessionLine.put("id", session.id());
        sessionLine.put("userId", session.userId());
        sessionLine.put("title", session.title());
        sessionLine.put("createdAt", session.createdAt());
        sessionLine.put("lastMessageAt", session.lastMessageAt());
        out.append(mapper.writeValueAsString(sessionLine)).append('\n');
        for (MessageDto row : rows) {
            LinkedHashMap<String, Object> messageLine = new LinkedHashMap<>();
            messageLine.put("type", "message");
            messageLine.put("id", row.id());
            messageLine.put("sessionId", row.sessionId());
            messageLine.put("role", row.role());
            messageLine.put("content", row.content());
            messageLine.put("metadata", row.metadata());
            messageLine.put("createdAt", row.createdAt());
            out.append(mapper.writeValueAsString(messageLine)).append('\n');
        }
        String filename = "session-" + id + ".jsonl";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(out.toString());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        sessionService.delete(id, PrincipalContext.requireUserId());
        return ResponseEntity.noContent().build();
    }

    /** Inline body for {@code POST /api/sessions} — only field is optional title. */
    public record CreateSessionBody(String title) {}
}
