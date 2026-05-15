package com.agentplatform.agent.ai;

import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

/**
 * LangChain4j AI Service contract for durable fact extraction.
 *
 * <p>The production memory pipeline still routes dynamically through
 * {@link BackgroundLlmClient} because this project supports a user-configured
 * provider pool and per-deploy fallback. Keeping this typed contract in the
 * codebase documents the target framework shape and lets a single-provider
 * deployment wire it explicitly later without changing prompt wording.
 */
@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "backgroundFactExtractorChatModel")
public interface BackgroundFactExtractor {

    @UserMessage("""
            Below are recent (USER, ASSISTANT) exchanges from the same conversation.
            Across all of them, extract durable user-specific facts, preferences, or
            rules worth remembering for future conversations. Combine related observations
            where it makes sense; skip transient one-off details.

            Output a JSON array, e.g.
            [
              {"kind":"preference","content":"用户喜欢简短中文回答"},
              {"kind":"fact","content":"用户的小狗叫旺财"}
            ]
            Allowed kind values: fact | preference | rule.
            If nothing worth remembering, output [] exactly.
            Output ONLY the JSON array, no prose, no code fence.

            {{exchanges}}
            """)
    String extractFacts(@V("exchanges") String exchanges);
}
