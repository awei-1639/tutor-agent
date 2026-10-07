package com.tutor.platform.llm;

import com.tutor.platform.config.LlmProperties;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 回归：流式请求地址必须按 baseUrl 的版本段收尾，不得再补一层版本段（智谱 /paas/v4 曾被多拼 /v1）。 */
class ChatStreamProviderClientTest {

    @Test
    void appendsChatCompletionsToBareDeepseekHost() {
        assertEndpoint("https://api.deepseek.com", "https://api.deepseek.com/chat/completions");
    }

    @Test
    void appendsChatCompletionsToVersionedEndpoints() {
        assertEndpoint("https://api.siliconflow.cn/v1", "https://api.siliconflow.cn/v1/chat/completions");
        assertEndpoint("http://localhost:11434/v1", "http://localhost:11434/v1/chat/completions");
    }

    @Test
    void appendsChatCompletionsToNonV1VersionSegments() {
        assertEndpoint("https://open.bigmodel.cn/api/paas/v4",
                "https://open.bigmodel.cn/api/paas/v4/chat/completions");
    }

    @Test
    void toleratesTrailingSlashInBaseUrl() {
        assertEndpoint("https://open.bigmodel.cn/api/paas/v4/",
                "https://open.bigmodel.cn/api/paas/v4/chat/completions");
    }

    private void assertEndpoint(String baseUrl, String expected) {
        var properties = new LlmProperties(
                new LlmProperties.Endpoint("test-key", baseUrl),
                new LlmProperties.Endpoint("test-key", baseUrl),
                null,
                new LlmProperties.Budget(1_000, 1_000),
                new LlmProperties.Timeout(10, 60, 120, 180),
                LlmProperties.TokenLimits.defaults());
        var client = new ChatStreamProviderClient(properties);

        URI uri = client.buildRequest("test-model", List.of(UserMessage.from("hi")), 16).uri();

        assertThat(uri).isEqualTo(URI.create(expected));
    }
}
