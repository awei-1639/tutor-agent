package com.tutor.platform.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.platform.config.LlmProperties;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** 流式 Chat Provider 请求适配器，负责 SSE 请求地址和消息载荷编码。 */
final class ChatStreamProviderClient {
    private final LlmProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    ChatStreamProviderClient(LlmProperties properties) { this.properties = properties; }

    HttpRequest buildRequest(String model, List<ChatMessage> messages, int maxOutputTokens) {
        return HttpRequest.newBuilder().uri(URI.create(endpoint()))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + properties.deepseek().apiKey())
                .timeout(Duration.ofSeconds(properties.timeout().chatSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(body(model, messages, maxOutputTokens), StandardCharsets.UTF_8))
                .build();
    }

    private String endpoint() {
        String base = properties.deepseek().baseUrl();
        String normalized = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return normalized.endsWith("/v1") ? normalized + "/chat/completions" : normalized + "/v1/chat/completions";
    }

    private String body(String model, List<ChatMessage> messages, int maxOutputTokens) {
        var root = mapper.createObjectNode();
        root.put("model", model); root.put("temperature", 0.3); root.put("stream", true); root.put("max_tokens", maxOutputTokens);
        // 流式调用显式索要用量与 finish_reason：结算精度和截断可见性都依赖它们。
        var streamOptions = mapper.createObjectNode();
        streamOptions.put("include_usage", true);
        root.set("stream_options", streamOptions);
        var array = mapper.createArrayNode();
        for (ChatMessage message : messages) {
            var node = mapper.createObjectNode();
            if (message instanceof SystemMessage system) { node.put("role", "system"); node.put("content", system.text()); }
            else if (message instanceof UserMessage user && user.hasSingleText()) { node.put("role", "user"); node.put("content", user.singleText()); }
            else if (message instanceof AiMessage ai && ai.text() != null) { node.put("role", "assistant"); node.put("content", ai.text()); }
            else throw new IllegalArgumentException("unsupported chat message type: " + message.type());
            array.add(node);
        }
        root.set("messages", array);
        try { return mapper.writeValueAsString(root); }
        catch (Exception e) { throw new IllegalStateException("failed to encode chat request", e); }
    }
}
