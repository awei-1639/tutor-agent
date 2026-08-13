package com.tutor.memory.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.config.Mem0Properties;
import com.tutor.memory.local.EpisodeStore;
import com.tutor.resume.PiiMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Mem0 Platform V3 REST 客户端。
 * 只负责 HTTP 与 JSON 映射，调用方负责异步调度和失败降级。
 */
@Component
public class Mem0Client {
    private static final Logger log = LoggerFactory.getLogger(Mem0Client.class);
    private static final String ADD_PATH = "/v3/memories/add/";
    private static final String SEARCH_PATH = "/v3/memories/search/";

    private final Mem0Properties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;

    public Mem0Client(Mem0Properties props) {
        this.props = props;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds()))
                .build();
    }

    public boolean enabled() {
        return props.configured();
    }

    public List<EpisodeStore.Episode> search(long userId, String query, String traceId) {
        if (!enabled()) return List.of();
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "query", PiiMasker.mask(query).masked(),
                    "filters", Map.of("user_id", Long.toString(userId)),
                    "top_k", 3,
                    "threshold", 0.1,
                    "rerank", false));
            JsonNode root = send("POST", SEARCH_PATH, body);
            List<EpisodeStore.Episode> result = new ArrayList<>();
            JsonNode items = root.isArray() ? root : root.path("results");
            for (JsonNode item : items) {
                String memory = item.path("memory").asText("").strip();
                if (memory.isBlank()) continue;
                memory = PiiMasker.mask(memory).masked();
                result.add(new EpisodeStore.Episode(
                        0L, userId, null, memory,
                        metadataList(item, "topics"), metadataList(item, "open_items")));
            }
            log.debug("Mem0 memory search user={} results={} trace={}", userId, result.size(), traceId);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Mem0 memory search failed", e);
        }
    }

    /** 添加一轮对话；Mem0 平台异步处理，响应只表示请求已排队。 */
    public void addConversation(long userId, String question, String answer, String traceId) {
        if (!enabled()) return;
        try {
            PiiMasker.MaskResult maskedQuestion = PiiMasker.mask(question);
            PiiMasker.MaskResult maskedAnswer = PiiMasker.mask(answer);
            String body = mapper.writeValueAsString(Map.of(
                    "user_id", Long.toString(userId),
                    "messages", List.of(
                            Map.of("role", "user", "content", maskedQuestion.masked()),
                            Map.of("role", "assistant", "content", maskedAnswer.masked())),
                    "metadata", Map.of("source", "personal-ai-tutor", "trace_id", traceId)));
            send("POST", ADD_PATH, body);
            log.debug("Mem0 memory add queued user={} trace={}", userId, traceId);
        } catch (Exception e) {
            throw new IllegalStateException("Mem0 memory add failed", e);
        }
    }

    /** 按用户删除远程记忆；user_id 是强制限定条件，禁止无过滤全量删除。 */
    public void deleteAllForUser(long userId) {
        if (!enabled()) return;
        try {
            send("DELETE", "/v1/memories?user_id="
                    + URLEncoder.encode(Long.toString(userId), StandardCharsets.UTF_8), "");
        } catch (Exception e) {
            throw new IllegalStateException("Mem0 memory deletion failed", e);
        }
    }

    private JsonNode send(String method, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(props.baseUrl()) + path))
                .timeout(Duration.ofSeconds(timeoutSeconds()))
                .header("Authorization", "Token " + props.apiKey())
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response;
        try {
            response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .orTimeout(timeoutSeconds(), TimeUnit.SECONDS)
                    .join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return mapper.readTree(response.body());
    }

    private static List<String> metadataList(JsonNode item, String key) {
        JsonNode metadata = item.path("metadata").path(key);
        if (!metadata.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        metadata.forEach(v -> {
            if (!v.asText("").isBlank()) values.add(v.asText());
        });
        return values;
    }

    private int timeoutSeconds() {
        return Math.max(1, props.timeoutSeconds());
    }

    private static String trimTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
}
