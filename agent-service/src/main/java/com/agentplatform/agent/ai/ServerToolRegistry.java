package com.agentplatform.agent.ai;

import com.anthropic.models.messages.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ServerToolRegistry {

    private final List<ServerToolCallback> callbacks;
    private final ObjectMapper mapper;

    public ServerToolRegistry(List<ServerToolCallback> callbacks, ObjectMapper mapper) {
        this.callbacks = callbacks == null ? List.of() : callbacks;
        this.mapper = mapper;
        validateUniqueNames(this.callbacks);
    }

    public List<Tool> toAnthropicTools() {
        List<Tool> out = new ArrayList<>(callbacks.size());
        for (ServerToolCallback callback : callbacks) {
            out.add(callback.toAnthropicTool(mapper));
        }
        return out;
    }

    public Map<String, ServerToolCallback> dispatchMap() {
        Map<String, ServerToolCallback> out = new LinkedHashMap<>();
        for (ServerToolCallback callback : callbacks) {
            out.put(callback.name(), callback);
        }
        return out;
    }

    private static void validateUniqueNames(List<ServerToolCallback> callbacks) {
        Set<String> seen = new HashSet<>();
        for (ServerToolCallback callback : callbacks) {
            String name = callback.name();
            if (name == null || name.isBlank()) {
                throw new IllegalStateException("server tool name is required");
            }
            if (!seen.add(name)) {
                throw new IllegalStateException("duplicate server tool name: " + name);
            }
        }
    }
}
