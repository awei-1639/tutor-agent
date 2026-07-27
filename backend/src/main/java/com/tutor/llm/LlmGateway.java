package com.tutor.llm;

import com.tutor.config.LlmProperties;
import com.tutor.contract.Purpose;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 全项目唯一的 LLM/Embedding 出口 (实现设计 6.1)。
 * 职责: purpose→model 路由、分级超时、轻量重试(Spike1结论)、日限额、llm_usage 记账。
 */
@Component
public class LlmGateway {
    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);
    private final LlmProperties props;
    private final JdbcTemplate jdbc;
    private final EmbeddingModel embeddingModel;

    public LlmGateway(LlmProperties props, JdbcTemplate jdbc) {
        this.props = props;
        this.jdbc = jdbc;
        this.embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(props.siliconflow().apiKey())
                .baseUrl(props.siliconflow().baseUrl())
                .modelName(props.routing().get("embed"))
                .timeout(Duration.ofSeconds(30))
                .build();
    }

    /** 查询/文档向量化。失败重试1次 (429/5xx/超时同一策略, Spike1结论: 轻量即可)。 */
    public float[] embed(String text, String traceId) {
        long t0 = System.currentTimeMillis();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Embedding e = embeddingModel.embed(text).content();
                recordUsage(traceId, Purpose.EMBED, props.routing().get("embed"),
                        text.length() / 2, 0, System.currentTimeMillis() - t0, "ok");
                return e.vector();
            } catch (RuntimeException ex) {
                last = ex;
                log.warn("embed attempt {} failed: {}", attempt, ex.getMessage());
            }
        }
        recordUsage(traceId, Purpose.EMBED, props.routing().get("embed"),
                0, 0, System.currentTimeMillis() - t0, "error");
        throw last;
    }

    /** 流式对话。首 token 前失败可由调用方决定是否重建; 网关负责限额检查与记账。 */
    public void chatStream(Purpose purpose, List<ChatMessage> messages, String traceId,
                           StreamingChatResponseHandler handler) {
        checkDailyBudget();
        String model = props.routing().getOrDefault(purpose.name().toLowerCase(), "deepseek-chat");
        OpenAiStreamingChatModel chat = OpenAiStreamingChatModel.builder()
                .apiKey(props.deepseek().apiKey())
                .baseUrl(props.deepseek().baseUrl())
                .modelName(model)
                .temperature(0.3)
                .timeout(Duration.ofSeconds(props.timeout().chatSeconds()))
                .build();
        long t0 = System.currentTimeMillis();
        chat.chat(messages, new StreamingChatResponseHandler() {
            @Override public void onPartialResponse(String token) { handler.onPartialResponse(token); }

            @Override public void onCompleteResponse(ChatResponse response) {
                TokenUsage u = response.tokenUsage();
                recordUsage(traceId, purpose, model,
                        u != null ? u.inputTokenCount() : 0,
                        u != null ? u.outputTokenCount() : 0,
                        System.currentTimeMillis() - t0, "ok");
                handler.onCompleteResponse(response);
            }

            @Override public void onError(Throwable error) {
                recordUsage(traceId, purpose, model, 0, 0, System.currentTimeMillis() - t0, "error");
                handler.onError(error);
            }
        });
    }

    /**
     * 非流式 JSON 调用 (画像抽取/router 等结构化用途)。
     * response_format=json_object + 失败重试1次 (Spike1结论: 轻量防御即可)。
     */
    public String chatJson(Purpose purpose, List<ChatMessage> messages, String traceId) {
        checkDailyBudget();
        String model = props.routing().getOrDefault(purpose.name().toLowerCase(), "deepseek-chat");
        OpenAiChatModel chat = OpenAiChatModel.builder()
                .apiKey(props.deepseek().apiKey())
                .baseUrl(props.deepseek().baseUrl())
                .modelName(model)
                .temperature(0.0)
                .responseFormat("json_object")
                .timeout(Duration.ofSeconds(purpose == Purpose.ROUTER
                        ? props.timeout().routerSeconds() : props.timeout().chatSeconds()))
                .build();
        long t0 = System.currentTimeMillis();
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                ChatResponse resp = chat.chat(messages);
                TokenUsage u = resp.tokenUsage();
                recordUsage(traceId, purpose, model,
                        u != null ? u.inputTokenCount() : 0,
                        u != null ? u.outputTokenCount() : 0,
                        System.currentTimeMillis() - t0, "ok");
                return resp.aiMessage().text();
            } catch (RuntimeException ex) {
                last = ex;
                log.warn("chatJson {} attempt {} failed: {}", purpose, attempt, ex.getMessage());
            }
        }
        recordUsage(traceId, purpose, model, 0, 0, System.currentTimeMillis() - t0, "error");
        throw last;
    }

    /**
     * 重排序 (SiliconFlow bge-reranker-v2-m3)。返回与docs等长的相关性分数数组。
     * 失败抛出由调用方降级 (降级矩阵: 重排失败→保持融合排序)。
     */
    public double[] rerank(String query, List<String> docs, String traceId) {
        long t0 = System.currentTimeMillis();
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var body = mapper.writeValueAsString(java.util.Map.of(
                    "model", "BAAI/bge-reranker-v2-m3", "query", query, "documents", docs));
            var client = java.net.http.HttpClient.newHttpClient();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(props.siliconflow().baseUrl() + "/rerank"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.siliconflow().apiKey())
                    .timeout(Duration.ofSeconds(15))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var resp = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) throw new IllegalStateException("rerank HTTP " + resp.statusCode());
            var root = mapper.readTree(resp.body());
            double[] scores = new double[docs.size()];
            for (var r : root.path("results")) {
                scores[r.path("index").asInt()] = r.path("relevance_score").asDouble();
            }
            recordUsage(traceId, Purpose.RERANK, "bge-reranker-v2-m3",
                    docs.stream().mapToInt(String::length).sum() / 2, 0,
                    System.currentTimeMillis() - t0, "ok");
            return scores;
        } catch (Exception e) {
            recordUsage(traceId, Purpose.RERANK, "bge-reranker-v2-m3", 0, 0,
                    System.currentTimeMillis() - t0, "error");
            throw e instanceof RuntimeException re ? re : new IllegalStateException(e);
        }
    }

    /** 日限额: 超限直接拒绝本轮 (单轮熔断在编排层用累计值判断, W2 接入)。 */
    private void checkDailyBudget() {
        Long used = jdbc.queryForObject(
                "SELECT COALESCE(SUM(tokens_in + tokens_out),0) FROM llm_usage WHERE created_at >= CURRENT_DATE",
                Long.class);
        if (used != null && used > props.budget().dailyTokenLimit()) {
            throw new IllegalStateException("每日token限额已用尽: " + used + "/" + props.budget().dailyTokenLimit());
        }
    }

    private void recordUsage(String traceId, Purpose purpose, String model,
                             long in, long out, long ms, String status) {
        try {
            jdbc.update("INSERT INTO llm_usage (trace_id, purpose, model, tokens_in, tokens_out, duration_ms, status) VALUES (?,?,?,?,?,?,?)",
                    traceId, purpose.name().toLowerCase(), model, in, out, (int) ms, status);
        } catch (Exception e) {
            log.error("llm_usage 记账失败(不阻塞主链路): {}", e.getMessage());
        }
    }
}
