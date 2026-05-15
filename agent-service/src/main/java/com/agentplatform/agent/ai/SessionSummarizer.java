package com.agentplatform.agent.ai;

import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "sessionSummarizerChatModel")
public interface SessionSummarizer {

    @UserMessage("""
            You maintain a compact rolling summary for a chat session.
            Rewrite the summary so it covers the transcript below. Keep durable decisions,
            user goals, constraints, unresolved tasks, references to important artifacts,
            and corrections. Drop filler, duplicate phrasing, and low-value chatter.
            Do not invent facts. Do not include XML/JSON/code fences.
            Target at most {{maxTokens}} tokens.

            Existing summary, if any:
            {{existingSummary}}

            New transcript segment to merge ({{deltaCount}} messages; summary will cover
            {{coveredAfterThisRun}} older messages total; the newest {{recentTailCount}}
            messages remain verbatim outside the summary):
            {{transcript}}
            """)
    String summarize(@V("existingSummary") String existingSummary,
                     @V("deltaCount") int deltaCount,
                     @V("coveredAfterThisRun") int coveredAfterThisRun,
                     @V("recentTailCount") int recentTailCount,
                     @V("maxTokens") int maxTokens,
                     @V("transcript") String transcript);
}
