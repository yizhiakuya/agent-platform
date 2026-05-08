package com.agentplatform.hub;

import com.agentplatform.hub.client.AuthInternalClient;
import com.agentplatform.security.InternalToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@TestPropertySource(properties = {
        "agent-platform.hub.mock-mode=true",
        "agent-platform.hub.mock-fake-latency-ms=50",
        "spring.cloud.nacos.discovery.register-enabled=false"
})
class InternalToolControllerTest {

    private static final String INTERNAL_TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==";

    @Autowired private WebApplicationContext webContext;
    @Autowired private ObjectMapper mapper;

    @MockitoBean private AuthInternalClient authClient;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(webContext).build();
    }

    @Test
    void mocked_tool_call_returns_fake_result() throws Exception {
        UUID deviceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        String body = mapper.writeValueAsString(mapper.createObjectNode()
                .put("deviceId", deviceId.toString())
                .put("userId", userId.toString())
                .put("toolName", "photos.list_recent")
                .set("args", mapper.createObjectNode().put("limit", 5)));

        MvcResult started = mvc.perform(post("/internal/tools/call")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(InternalToken.HEADER, INTERNAL_TOKEN)
                        .content(body))
                .andExpect(request().asyncStarted())
                .andReturn();

        // Wait for the DeferredResult to be set by the mock session's scheduled task.
        Object asyncResult = started.getAsyncResult(2000);
        assertThat(asyncResult).isNotNull();

        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value.mocked").value(true))
                .andExpect(jsonPath("$.value.echoed_params.tool").value("photos.list_recent"))
                .andExpect(jsonPath("$.value.echoed_params.args.limit").value(5))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void short_timeout_resolves_with_TOOL_TIMEOUT_error() throws Exception {
        UUID deviceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        // Mock latency 50ms, timeoutMs=10 → guaranteed timeout fires before fake result.
        String body = mapper.writeValueAsString(mapper.createObjectNode()
                .put("deviceId", deviceId.toString())
                .put("userId", userId.toString())
                .put("toolName", "photos.list_recent")
                .put("timeoutMs", 10)
                .set("args", mapper.createObjectNode().put("limit", 5)));

        MvcResult started = mvc.perform(post("/internal/tools/call")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(InternalToken.HEADER, INTERNAL_TOKEN)
                        .content(body))
                .andExpect(request().asyncStarted())
                .andReturn();

        Object asyncResult = started.getAsyncResult(2000);
        assertThat(asyncResult).isNotNull();

        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32002))      // TOOL_TIMEOUT
                .andExpect(jsonPath("$.value").doesNotExist());
    }

    @Test
    void missing_deviceId_returns_400() throws Exception {
        String body = mapper.writeValueAsString(mapper.createObjectNode()
                .put("toolName", "photos.list_recent"));

        mvc.perform(post("/internal/tools/call")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(InternalToken.HEADER, INTERNAL_TOKEN)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
