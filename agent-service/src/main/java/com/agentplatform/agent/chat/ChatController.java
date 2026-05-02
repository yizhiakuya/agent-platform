package com.agentplatform.agent.chat;

import com.agentplatform.security.PrincipalContext;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    /** SSE timeout (ms). LLM tool-call rounds + streaming text can take a while. */
    private static final long SSE_TIMEOUT_MS = 600_000L;

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest req) {
        UUID userId = PrincipalContext.requireUserId();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        chatService.handle(userId, req, emitter);
        return emitter;
    }
}
