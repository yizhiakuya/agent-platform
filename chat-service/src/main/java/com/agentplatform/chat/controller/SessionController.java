package com.agentplatform.chat.controller;

import com.agentplatform.api.chat.MessageDto;
import com.agentplatform.api.chat.SessionDto;
import com.agentplatform.chat.service.MessageService;
import com.agentplatform.chat.service.SessionService;
import com.agentplatform.security.PrincipalContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final MessageService messageService;

    public SessionController(SessionService sessionService, MessageService messageService) {
        this.sessionService = sessionService;
        this.messageService = messageService;
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

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        sessionService.delete(id, PrincipalContext.requireUserId());
        return ResponseEntity.noContent().build();
    }

    /** Inline body for {@code POST /api/sessions} — only field is optional title. */
    public record CreateSessionBody(String title) {}
}
