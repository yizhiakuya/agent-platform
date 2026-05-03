package com.agentplatform.agent.ai;

import com.anthropic.client.AnthropicClient;

/** 配置好的 LLM provider:SDK 客户端 + 模型 ID + 名字。fallback chain 走这个 list。 */
public record ConfiguredProvider(String name, AnthropicClient client, String model) {}
