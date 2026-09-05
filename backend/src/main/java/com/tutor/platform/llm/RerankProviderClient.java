package com.tutor.platform.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.platform.config.LlmProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** 重排 Provider 的 HTTP 适配器，只负责请求、响应解析和结果完整性校验。 */
final class RerankProviderClient {
    private static final String MODEL = "BAAI/bge-reranker-v2-m3";
    private final LlmProperties properties;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    RerankProviderClient(LlmProperties properties) {
        this.properties = properties;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    double[] rerank(String query, List<String> documents) {
        try {
            String body = mapper.writeValueAsString(Map.of("model", MODEL, "query", query, "documents", documents));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(properties.siliconflow().baseUrl() + "/rerank"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.siliconflow().apiKey())
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("rerank HTTP " + response.statusCode());
            double[] scores = new double[documents.size()];
            for (var result : mapper.readTree(response.body()).path("results")) {
                int index = result.path("index").asInt(-1);
                if (index >= 0 && index < scores.length) scores[index] = result.path("relevance_score").asDouble();
            }
            return scores;
        } catch (Exception e) {
            throw e instanceof RuntimeException runtime ? runtime : new IllegalStateException(e);
        }
    }
}
