package com.agentplatform.chat.controller;

import com.agentplatform.api.chat.CreateSessionRequest;
import com.agentplatform.api.chat.MessageDto;
import com.agentplatform.api.chat.SessionDto;
import com.agentplatform.api.chat.WriteMessageRequest;
import com.agentplatform.chat.service.MessageService;
import com.agentplatform.chat.service.SessionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/internal")
public class InternalChatController {

    private final SessionService sessionService;
    private final MessageService messageService;

    public InternalChatController(SessionService sessionService, MessageService messageService) {
        this.sessionService = sessionService;
        this.messageService = messageService;
    }

    /** Create a session on behalf of agent-service when the LLM starts a new conversation. */
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionDto createSession(@RequestBody CreateSessionRequest req) {
        if (req.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId required");
        }
        return sessionService.create(req.userId(), req.title());
    }

    /** Append a single message to an existing session. */
    @PostMapping("/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageDto writeMessage(@RequestBody WriteMessageRequest req) {
        return messageService.write(req);
    }

    /**
     * List the message history for a session. Used by agent-service to feed
     * prior turns into the LLM so the conversation actually has context.
     */
    @GetMapping("/sessions/{id}/messages")
    public List<MessageDto> listMessages(@PathVariable UUID id, @RequestParam UUID userId) {
        return messageService.listBySession(id, userId);
    }
}
