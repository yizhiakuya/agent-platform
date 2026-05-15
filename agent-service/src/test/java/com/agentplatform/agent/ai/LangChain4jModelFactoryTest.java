package com.agentplatform.agent.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LangChain4jModelFactoryTest {

    @Test
    void ensureOpenAiV1BaseUrlAppendsVersionForResponsesClient() {
        assertThat(LangChain4jModelFactory.ensureOpenAiV1BaseUrl("https://subapi.rainaki.top"))
                .isEqualTo("https://subapi.rainaki.top/v1");
        assertThat(LangChain4jModelFactory.ensureOpenAiV1BaseUrl("https://api.openai.com/v1"))
                .isEqualTo("https://api.openai.com/v1");
        assertThat(LangChain4jModelFactory.ensureOpenAiV1BaseUrl("https://api.openai.com/v1/"))
                .isEqualTo("https://api.openai.com/v1");
    }
}
