package com.sau.gym.admin.agent.config;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 作者:hfj
 * 功能:LangChain4j 底层模型与记忆配置
 * 说明:
 * 前台客服编排已经切换到自建 GymAgentHarness + ReActRuntime + LlmClient，
 * 这里不再创建 GymAgentAssistant。保留的 ChatModel 与 ChatMemoryProvider
 * 仅服务于 MCP 相关 Assistant，属于本轮明确保留的底层依赖。
 * 日期: 2026/4/23 14:29
 */
@Configuration
public class LangChain4jAgentConfig {

    /**
     * 创建大模型对象。
     * MCP 的 Assistant 仍使用该 Bean，前台客服不再使用。
     */
    @Bean
    public ChatModel gymChatModel(
            @Value("${gym.ai.base-url}") String baseUrl,
            @Value("${gym.ai.api-key}") String apiKey,
            @Value("${gym.ai.model-name}") String modelName,
            @Value("${gym.ai.temperature:0.1}") Double temperature
    ) {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(temperature)
                .logRequests(true)
                .logResponses(true)
                .build();
    }

    /**
     * MCP Assistant 使用的会话记忆提供器。
     * 前台客服会话历史已经统一到 AgentSessionMessageStore，不再使用该记忆窗口。
     */
    @Bean
    public ChatMemoryProvider gymChatMemoryProvider() {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(20)
                .build();
    }
}
