package com.agentplatform.agent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionResultTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void errorEscapesMessagesAsValidJson() throws Exception {
        String message = "bad \"quote\" \\ path\nnext line\u0001";

        ExecutionResult result = ExecutionResult.error(message);
        JsonNode parsed = mapper.readTree(result.jsonText());

        assertThat(parsed.path("error").asText()).isEqualTo(message);
        assertThat(result.images()).isEmpty();
    }
}
