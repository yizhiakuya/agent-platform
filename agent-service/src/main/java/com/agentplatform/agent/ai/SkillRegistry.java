package com.agentplatform.agent.ai;

import com.agentplatform.agent.client.InternalChatFeignClient;
import com.agentplatform.api.chat.RuntimeSkillDto;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Scans {@code classpath:skills/<slug>/SKILL.md} once at startup, parses each
 * file's YAML front-matter into a {@link SkillDef}, and exposes lookup by
 * name.
 *
 * <p>Front-matter format (matches Claude Code's skill convention):
 * <pre>
 * ---
 * name: photos-search
 * description: One-line summary used in the skill listing.
 * ---
 * (markdown body — the playbook)
 * </pre>
 *
 * <p>SnakeYAML is on the classpath transitively via Spring Boot (used by
 * {@code application.yaml} parsing), so we reuse it. A skill whose file is
 * malformed or missing required keys is skipped with a {@code WARN} log;
 * startup never fails because of one bad skill.
 *
 * <p>Two consumers:
 * <ul>
 *   <li>{@link #all()} — prompt assembly enumerates all skills and injects
 *       just {@code name + description} into the system prompt as a listing.
 *   <li>{@link #get(String)} — {@link SkillLoadCallback} resolves a name back
 *       to a body when the LLM decides to pull a playbook.
 * </ul>
 */
@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private static final String SKILL_PATTERN = "classpath:skills/*/SKILL.md";

    /** Insertion-ordered so the listing is stable across restarts. */
    private final Map<String, SkillDef> skills = new LinkedHashMap<>();
    private final InternalChatFeignClient chatClient;

    public SkillRegistry(InternalChatFeignClient chatClient) {
        this.chatClient = chatClient;
    }

    @PostConstruct
    void load() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(SKILL_PATTERN);
        } catch (IOException e) {
            log.warn("Failed to scan classpath for skills (pattern '{}'): {}",
                    SKILL_PATTERN, e.getMessage());
            return;
        }
        if (resources.length == 0) {
            log.info("No skills found under '{}'.", SKILL_PATTERN);
            return;
        }
        for (Resource r : resources) {
            try {
                SkillDef def = parse(r);
                if (def == null) continue;
                SkillDef prev = skills.put(def.name(), def);
                if (prev != null) {
                    log.warn("Skill name '{}' defined more than once; later definition wins.",
                            def.name());
                }
            } catch (Exception e) {
                warnSkill(r, "Failed to parse skill '{}': {}; skipping.", e.getMessage());
            }
        }
        log.info("Loaded {} skill(s): {}", skills.size(), skills.keySet());
    }

    /**
     * Parse one SKILL.md file. Returns {@code null} (and logs a warn) if the
     * file is missing front-matter or required keys.
     */
    private SkillDef parse(Resource resource) throws IOException {
        String raw;
        try (InputStream in = resource.getInputStream()) {
            raw = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }
        if (raw == null || raw.isBlank()) {
            warnSkill(resource, "Skill '{}' is empty; skipping.");
            return null;
        }

        // Normalize line endings so the front-matter delimiters match
        // regardless of CRLF/LF on disk.
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');

        if (!normalized.startsWith("---\n")) {
            warnSkill(resource, "Skill '{}' missing leading '---' YAML front-matter; skipping.");
            return null;
        }
        // Find the closing "---" delimiter on its own line.
        int closing = normalized.indexOf("\n---\n", 4);
        if (closing < 0) {
            // Allow the file to end immediately after the front-matter (no
            // body). Tolerate trailing-newline-less '---' too.
            closing = normalized.indexOf("\n---", 4);
            if (closing < 0) {
                warnSkill(resource, "Skill '{}' missing closing '---' for YAML front-matter; skipping.");
                return null;
            }
        }
        String yamlBlock = normalized.substring(4, closing); // exclusive of closing
        // Body starts after the closing delimiter line.
        int bodyStart = normalized.indexOf('\n', closing + 1);
        String body = bodyStart < 0 ? "" : normalized.substring(bodyStart + 1);

        Map<String, Object> meta;
        try {
            Object loaded = new Yaml().load(yamlBlock);
            if (loaded instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) m;
                meta = casted;
            } else {
                warnSkill(resource, "Skill '{}' front-matter is not a YAML mapping; skipping.");
                return null;
            }
        } catch (RuntimeException e) {
            warnSkill(resource, "Skill '{}' front-matter YAML parse failed: {}; skipping.", e.getMessage());
            return null;
        }

        Object nameObj = meta.get("name");
        Object descObj = meta.get("description");
        if (!(nameObj instanceof String name) || name.isBlank()) {
            warnSkill(resource, "Skill '{}' missing 'name' in front-matter; skipping.");
            return null;
        }
        String description = (descObj instanceof String s) ? s : "";

        return new SkillDef(name.trim(), description.trim(), body.stripLeading());
    }

    private static void warnSkill(Resource resource, String message) {
        if (log.isWarnEnabled()) {
            log.warn(message, safeUri(resource));
        }
    }

    private static void warnSkill(Resource resource, String message, String detail) {
        if (log.isWarnEnabled()) {
            log.warn(message, safeUri(resource), detail);
        }
    }

    private static String safeUri(Resource r) {
        try {
            return r.getURI().toString();
        } catch (IOException e) {
            return r.getDescription();
        }
    }

    /** All loaded skills, in load order. Never null. */
    public Collection<SkillDef> all() {
        return Collections.unmodifiableCollection(skills.values());
    }

    /** Built-in skills plus this user's runtime skills. Runtime definitions override by name. */
    public Collection<SkillDef> all(UUID userId) {
        if (userId == null) {
            return all();
        }
        LinkedHashMap<String, SkillDef> merged = new LinkedHashMap<>(skills);
        try {
            List<RuntimeSkillDto> rows = chatClient.listRuntimeSkills(userId, false);
            if (rows != null) {
                for (RuntimeSkillDto row : rows) {
                    if (row == null || !row.enabled()) continue;
                    merged.put(row.name(), new SkillDef(row.name(), row.description(), row.body()));
                }
            }
        } catch (Exception e) {
            log.debug("Failed to load runtime skills for user {}: {}", userId, e.getMessage());
        }
        return Collections.unmodifiableCollection(merged.values());
    }

    /** Look up a skill by its declared {@code name}. */
    public Optional<SkillDef> get(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(skills.get(name));
    }

    /** Runtime skill first, then packaged skill fallback. */
    public Optional<SkillDef> get(UUID userId, String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        if (userId != null) {
            try {
                RuntimeSkillDto runtime = chatClient.getRuntimeSkill(name, userId);
                if (runtime != null && runtime.enabled()) {
                    return Optional.of(new SkillDef(runtime.name(), runtime.description(), runtime.body()));
                }
            } catch (Exception e) {
                log.debug("Runtime skill lookup missed/failed for user {} name '{}': {}",
                        userId, name, e.getMessage());
            }
        }
        return get(name);
    }
}
