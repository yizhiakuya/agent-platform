package com.agentplatform.agent.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ChatServiceProviderFailureMessageTest {

    @Test
    void hidesRawProvider503AccountPoolError() {
        RuntimeException error = new RuntimeException(
                "503: {error={message=No available accounts: no available accounts, type=api_error}, type=error}");

        String message = ChatService.userFacingProviderFailureMessage(error);

        assertEquals("模型服务暂时不可用，可能是上游账号池没有可用账号。请稍后再试。", message);
        assertFalse(message.contains("503"));
        assertFalse(message.contains("api_error"));
        assertFalse(message.contains("No available accounts"));
    }

    @Test
    void mapsUnknownProviderFailureToGenericModelMessage() {
        String message = ChatService.userFacingProviderFailureMessage(new RuntimeException("upstream exploded"));

        assertEquals("模型服务暂时无法完成请求，请稍后再试。", message);
    }

    @Test
    void genericFailuresDoNotExposeRawExceptionDetails() {
        String message = ChatService.userFacingGeneralFailureMessage(new IllegalStateException("database detail"));

        assertEquals("请求处理失败，请稍后再试。", message);
    }
}
