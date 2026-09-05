package com.tutor.conversation.memory.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.platform.config.Mem0Properties;
import com.tutor.conversation.memory.local.EpisodeStore;
import com.tutor.conversation.memory.policy.MemoryAdmissionPolicy;
import com.tutor.identity.resume.PiiMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

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
    private static final String LIST_PATH = "/v3/memories/";
    private static final String SEARCH_PATH = "/v3/memories/search/";
    private static final int DISCOVERY_PAGE_SIZE = 200;
    private static final int MAX_DISCOVERY_PAGES = 25;

    private final Mem0Properties props;
    private final MemoryAdmissionPolicy admission;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public Mem0Client(Mem0Properties props) {
        this(props, new MemoryAdmissionPolicy());
    }

    @Autowired
    public Mem0Client(Mem0Properties props, MemoryAdmissionPolicy admission) {
        this(props, admission, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, props.timeoutSeconds())))
                .build(), new ObjectMapper());
    }

    Mem0Client(Mem0Properties props, MemoryAdmissionPolicy admission, HttpClient http,
               ObjectMapper mapper) {
        this.props = props;
        this.admission = admission;
        this.http = http;
        this.mapper = mapper;
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
            if (!items.isArray()) {
                throw new IllegalStateException("Mem0 search response has no results array");
            }
            for (JsonNode item : items) {
                if (!item.isObject() || !belongsToUser(item, userId)) continue;
                String memory = item.path("memory").asText("").strip();
                if (memory.isBlank()) continue;
                memory = PiiMasker.mask(memory).masked();
                List<String> topics = maskedMetadataList(item, "topics");
                List<String> openItems = maskedMetadataList(item, "open_items");
                // 远程数据是不可信输入：脱敏后仍需经过本地准入和提示注入校验。
                if (!admission.acceptsEpisode(memory, topics, openItems)) continue;
                double relevance = item.has("score") ? item.path("score").asDouble(0D)
                        : item.path("similarity").asDouble(0D);
                if (!Double.isFinite(relevance)) relevance = 0D;
                relevance = Math.clamp(relevance, 0D, 1D);
                long localMemoryId = item.path("metadata").path("memory_id").asLong(0L);
                String remoteMemoryId = item.path("id").asText("").strip();
                result.add(new EpisodeStore.Episode(
                        localMemoryId, userId, null, memory,
                        topics, openItems, relevance, remoteMemoryId.isBlank() ? null : remoteMemoryId));
            }
            log.debug("Mem0 memory search user={} results={} trace={}", userId, result.size(), traceId);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Mem0 memory search failed", e);
        }
    }

    /**
     * 明确禁止写入原始问题/回答。将该方法保留为硬失败，避免后续调用方
     * 意外重新引入粗粒度的远端持久化。
     */
    public void addConversation(long userId, String question, String answer, String traceId) {
        throw new IllegalStateException("raw conversation memory writes are disabled; submit an admitted summary");
    }

    /** 写入一条已准入、经 PII 筛查的摘要，而非整轮对话。 */
    public void addAdmittedMemory(long userId, String summary, List<String> topics, String traceId) {
        addAdmittedMemory(userId, summary, topics, List.of(), traceId);
    }

    /** 写入一条已准入摘要及其未完成事项；不接收原始问答。 */
    public void addAdmittedMemory(long userId, String summary, List<String> topics,
                                  List<String> openItems, String traceId) {
        addAdmittedMemory(userId, 0L, 0L, summary, topics, openItems, traceId);
    }

    /** 带本地记忆版本标识的幂等候选写入。 */
    public void addAdmittedMemory(long userId, long memoryId, long memoryGeneration,
                                  String summary, List<String> topics, List<String> openItems,
                                  String traceId) {
        if (!enabled()) return;
        if (!admission.acceptsEpisode(summary, topics, openItems)) {
            throw new IllegalArgumentException("memory record rejected by admission policy");
        }
        try {
            PiiMasker.MaskResult masked = PiiMasker.mask(summary);
            String body = mapper.writeValueAsString(Map.of(
                    "user_id", Long.toString(userId),
                    "messages", List.of(
                    Map.of("role", "user", "content", masked.masked())),
                    "metadata", Map.of("source", "personal-ai-tutor-admitted", "trace_id", traceId,
                            "user_id", Long.toString(userId),
                            "topics", topics == null ? List.of() : topics,
                            "open_items", openItems == null ? List.of() : openItems,
                            "memory_id", memoryId,
                            "memory_generation", memoryGeneration,
                            "idempotency_key", userId + ":" + memoryId + ":" + memoryGeneration)));
            send("POST", ADD_PATH, body);
            log.debug("Mem0 admitted memory add queued user={} trace={}", userId, traceId);
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

    /** 按 Mem0 返回的远端 UUID 删除单条记忆。 */
    public void deleteMemory(String remoteMemoryId) {
        if (!enabled()) return;
        requireRemoteMemoryId(remoteMemoryId);
        try {
            send("DELETE", "/v1/memories/" + remoteMemoryId + "/", "");
        } catch (Exception e) {
            throw new IllegalStateException("Mem0 single memory deletion failed", e);
        }
    }

    /**
     * 当本地删除发生在首次远程召回之前，按用户限定范围发现并删除远端副本。
     * 不跟随服务端返回的 next URL，避免把外部 URL 当作可调用地址；只使用受控页码。
     */
    public boolean deleteMemoryForLocalId(long userId, long memoryId) {
        if (!enabled()) return false;
        if (memoryId <= 0) throw new IllegalArgumentException("invalid local memory id");
        try {
            boolean deleted = false;
            for (int page = 1; page <= MAX_DISCOVERY_PAGES; page++) {
                String body = mapper.writeValueAsString(Map.of(
                        "filters", Map.of("user_id", Long.toString(userId)),
                        "show_expired", true,
                        "page", page,
                        "page_size", DISCOVERY_PAGE_SIZE,
                        "fields", List.of("id", "metadata")));
                JsonNode root = send("POST", LIST_PATH + "?page=" + page
                        + "&page_size=" + DISCOVERY_PAGE_SIZE, body);
                JsonNode items = root.isArray() ? root : root.path("results");
                if (!items.isArray()) throw new IllegalStateException("Mem0 list response has no results array");
                int itemCount = 0;
                for (JsonNode item : items) {
                    itemCount++;
                    if (!item.isObject() || !belongsToUser(item, userId)) continue;
                    if (item.path("metadata").path("memory_id").asLong(0L) != memoryId) continue;
                    String remoteId = item.path("id").asText("").strip();
                    if (remoteId.isBlank()) continue;
                    requireRemoteMemoryId(remoteId);
                    deleteMemory(remoteId);
                    deleted = true;
                }
                String next = root.path("next").asText("").strip();
                if (itemCount < DISCOVERY_PAGE_SIZE || next.isBlank()) return deleted;
            }
            throw new IllegalStateException("Mem0 memory discovery exceeded page limit");
        } catch (Exception e) {
            if (e instanceof IllegalStateException illegal
                    && illegal.getMessage() != null
                    && illegal.getMessage().startsWith("Mem0 memory discovery exceeded")) {
                throw illegal;
            }
            throw new IllegalStateException("Mem0 memory discovery failed", e);
        }
    }

    private JsonNode send(String method, String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(trimTrailingSlash(props.baseUrl()) + path))
                .timeout(Duration.ofSeconds(timeoutSeconds()))
                .header("Authorization", "Token " + props.apiKey())
                .header("Accept", "application/json")
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
        if (response.body() == null || response.body().isBlank()) return mapper.createObjectNode();
        return mapper.readTree(response.body());
    }

    private static List<String> maskedMetadataList(JsonNode item, String key) {
        JsonNode metadata = item.path("metadata").path(key);
        if (!metadata.isArray()) return List.of();
        List<String> values = new ArrayList<>();
        metadata.forEach(v -> {
            String value = PiiMasker.mask(v.asText("")).masked().strip();
            if (!value.isBlank()) values.add(value);
        });
        return values;
    }

    private static boolean belongsToUser(JsonNode item, long userId) {
        JsonNode candidate = item.has("user_id")
                ? item.path("user_id")
                : item.path("metadata").path("user_id");
        // 远端过滤条件不是授权证明；缺少归属字段时必须丢弃，避免跨用户结果被误注入。
        if (candidate.isMissingNode() || candidate.isNull() || candidate.asText("").isBlank()) return false;
        return Long.toString(userId).equals(candidate.asText());
    }

    private static void requireRemoteMemoryId(String remoteMemoryId) {
        com.tutor.conversation.memory.RemoteMemoryId.requireValid(remoteMemoryId);
    }

    private int timeoutSeconds() {
        return Math.max(1, props.timeoutSeconds());
    }

    private static String trimTrailingSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }
}
