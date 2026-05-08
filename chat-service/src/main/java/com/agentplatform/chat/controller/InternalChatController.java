package com.agentplatform.chat.controller;

import com.agentplatform.api.chat.CreateSessionRequest;
import com.agentplatform.api.chat.MessageDto;
import com.agentplatform.api.chat.RuntimeSkillDto;
import com.agentplatform.api.chat.SessionArtifactDto;
import com.agentplatform.api.chat.SessionContextSummaryDto;
import com.agentplatform.api.chat.SessionDto;
import com.agentplatform.api.chat.UpsertRuntimeSkillRequest;
import com.agentplatform.api.chat.UpsertSessionArtifactRequest;
import com.agentplatform.api.chat.UpsertSessionContextSummaryRequest;
import com.agentplatform.api.chat.WriteMessageRequest;
import com.agentplatform.chat.service.MessageService;
import com.agentplatform.chat.service.RuntimeSkillService;
import com.agentplatform.chat.service.SessionArtifactService;
import com.agentplatform.chat.service.SessionContextSummaryService;
import com.agentplatform.chat.service.SessionService;
import org.springframework.web.bind.annotation.DeleteMapping;
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
    private final SessionArtifactService artifactService;
    private final SessionContextSummaryService summaryService;
    private final RuntimeSkillService runtimeSkillService;

    public InternalChatController(SessionService sessionService,
                                  MessageService messageService,
                                  SessionArtifactService artifactService,
                                  SessionContextSummaryService summaryService,
                                  RuntimeSkillService runtimeSkillService) {
        this.sessionService = sessionService;
        this.messageService = messageService;
        this.artifactService = artifactService;
        this.summaryService = summaryService;
        this.runtimeSkillService = runtimeSkillService;
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

    /** Store one lightweight artifact pointer for the current conversation working set. */
    @PostMapping("/session-artifacts")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionArtifactDto upsertArtifact(@RequestBody UpsertSessionArtifactRequest req) {
        return artifactService.upsert(req);
    }

    /** Recent session working-set artifacts, newest first. */
    @GetMapping("/sessions/{id}/artifacts")
    public List<SessionArtifactDto> listArtifacts(@PathVariable UUID id,
                                                  @RequestParam UUID userId,
                                                  @RequestParam(defaultValue = "12") int limit) {
        return artifactService.listRecent(id, userId, limit);
    }

    /** Rolling summary of older conversation turns, if one exists. */
    @GetMapping("/sessions/{id}/context-summary")
    public SessionContextSummaryDto getContextSummary(@PathVariable UUID id, @RequestParam UUID userId) {
        return summaryService.get(id, userId);
    }

    /** Replace the compact summary for older turns in one session. */
    @PostMapping("/session-context-summaries")
    @ResponseStatus(HttpStatus.CREATED)
    public SessionContextSummaryDto upsertContextSummary(@RequestBody UpsertSessionContextSummaryRequest req) {
        return summaryService.upsert(req);
    }

    @GetMapping("/runtime-skills")
    public List<RuntimeSkillDto> listRuntimeSkills(@RequestParam UUID userId,
                                                   @RequestParam(defaultValue = "false") boolean includeDisabled) {
        return runtimeSkillService.list(userId, includeDisabled);
    }

    @GetMapping("/runtime-skills/{name}")
    public RuntimeSkillDto getRuntimeSkill(@PathVariable String name, @RequestParam UUID userId) {
        RuntimeSkillDto skill = runtimeSkillService.get(userId, name);
        if (skill == null || !skill.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "skill not found");
        }
        return skill;
    }

    @PostMapping("/runtime-skills")
    @ResponseStatus(HttpStatus.CREATED)
    public RuntimeSkillDto upsertRuntimeSkill(@RequestBody UpsertRuntimeSkillRequest req) {
        return runtimeSkillService.upsert(req);
    }

    @DeleteMapping("/runtime-skills/{name}")
    public void deleteRuntimeSkill(@PathVariable String name, @RequestParam UUID userId) {
        runtimeSkillService.delete(userId, name);
    }
}
