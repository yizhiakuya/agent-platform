package com.agentplatform.agent.ai;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads the layered agent persona markdown set from
 * {@code classpath:persona/SOUL.md|IDENTITY.md|AGENTS.md|TOOLS.md} once at
 * startup and caches them as a {@link PersonaBundle}.
 *
 * <p>The split mirrors OpenClaw:
 * <ul>
 *   <li>SOUL — behavioral identity (who you are, how you behave when stuck).</li>
 *   <li>IDENTITY — name / vibe / emoji self-intro (short, user-overridable).</li>
 *   <li>AGENTS — operational constraints (startup order, red lines, decision tree).</li>
 *   <li>TOOLS — environment specifics (bound devices, tool conventions, per-tool gotchas).</li>
 * </ul>
 *
 * <p>{@link #getBundle()} is read by the prompt-assembly path in ChatService on
 * every request. Missing files log a warning and fall through to either a
 * minimal SOUL fallback or empty string for the others, so the agent can still
 * boot in degraded form.
 *
 * <p>{@link #getPersona()} is preserved for backwards compatibility and
 * returns {@code bundle.soul()} so older call sites keep working.
 */
@Component
public class PersonaLoader {

    private static final Logger log = LoggerFactory.getLogger(PersonaLoader.class);

    private static final String SOUL_RESOURCE = "persona/SOUL.md";
    private static final String IDENTITY_RESOURCE = "persona/IDENTITY.md";
    private static final String AGENTS_RESOURCE = "persona/AGENTS.md";
    private static final String TOOLS_RESOURCE = "persona/TOOLS.md";

    private static final String FALLBACK_SOUL = """
            你是一个手机 agent。简短中文回答。优先用 tool 完成实际工作,不要寒暄。
            """;

    private PersonaBundle bundle = new PersonaBundle(FALLBACK_SOUL, "", "", "");

    @PostConstruct
    void load() {
        String soul = loadOrDefault(SOUL_RESOURCE, FALLBACK_SOUL);
        String identity = loadOrDefault(IDENTITY_RESOURCE, "");
        String agents = loadOrDefault(AGENTS_RESOURCE, "");
        String tools = loadOrDefault(TOOLS_RESOURCE, "");
        this.bundle = new PersonaBundle(soul, identity, agents, tools);
        log.info("Loaded persona bundle (SOUL={} chars, IDENTITY={} chars, AGENTS={} chars, TOOLS={} chars).",
                soul.length(), identity.length(), agents.length(), tools.length());
    }

    private String loadOrDefault(String resourcePath, String fallback) {
        Resource resource = new ClassPathResource(resourcePath);
        if (!resource.exists()) {
            log.warn("Persona resource '{}' not found on classpath; using fallback.", resourcePath);
            return fallback;
        }
        try (InputStream in = resource.getInputStream()) {
            String text = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            if (text == null || text.isBlank()) {
                log.warn("Persona resource '{}' is empty; using fallback.", resourcePath);
                return fallback;
            }
            return text;
        } catch (IOException e) {
            log.warn("Failed to read persona resource '{}': {}; using fallback.",
                    resourcePath, e.getMessage());
            return fallback;
        }
    }

    /** The full layered persona set. Never null; missing files yield empty fields. */
    public PersonaBundle getBundle() {
        return bundle;
    }

    /**
     * Backwards-compatible accessor — returns the SOUL section only. New code
     * should use {@link #getBundle()} so it can place each section under the
     * right header.
     */
    public String getPersona() {
        return bundle.soul();
    }
}
