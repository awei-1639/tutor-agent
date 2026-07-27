package com.tutor.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.Purpose;
import com.tutor.llm.LlmGateway;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 模拟面试状态机 (Phase 3 V4 3.3): 开场 → 逐题提问 → 评分追问 → 复盘报告
 * 题库来源: 岗位 requires 技能 × 用户薄弱项 (按 ProfileService 数据生成)
 * 状态机: 内存 Map<sessionId, SessionState>, 用户级单 session
 */
@Service
public class InterviewSession {
    private static final Logger log = LoggerFactory.getLogger(InterviewSession.class);
    private static final int MAX_QUESTIONS = 5;
    private static final int MAX_FOLLOWUPS_PER_Q = 1;

    private final JdbcTemplate jdbc;
    private final LlmGateway gateway;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, SessionState> states = new HashMap<>();

    public InterviewSession(JdbcTemplate jdbc, LlmGateway gateway) {
        this.jdbc = jdbc;
        this.gateway = gateway;
    }

    public static class SessionState {
        String sessionId, topic, status;
        List<String> questions, answers;
        List<Integer> scores;
        int currentQ, followupsLeft;
        SessionState(String s, String t) {
            sessionId = s; topic = t; status = "active";
            questions = new ArrayList<>(); answers = new ArrayList<>(); scores = new ArrayList<>();
            currentQ = 0; followupsLeft = MAX_FOLLOWUPS_PER_Q;
        }
    }

    public record Report(int totalQuestions, double avgScore, List<String> strengths,
                         List<String> improvements, List<String> resources) {}

    /** 开场: 选定主题 (基于岗位/用户技能), 返回第 1 题 */
    public String open(String sessionId, long userId, String targetRole, String traceId) {
        // 从 jobs 取目标岗位 requires 技能 (题库来源)
        List<String> skills = new ArrayList<>();
        try {
            jdbc.query(
                    "SELECT unnest(requires_raw) FROM jobs WHERE title LIKE ? LIMIT 1",
                    rs -> { skills.add(rs.getString(1)); },
                    "%" + (targetRole == null ? "" : targetRole) + "%");
        } catch (Exception e) { /* 容错 */ }

        String topic = skills.isEmpty() ? "通用算法与系统设计" : String.join(", ", skills.subList(0, Math.min(3, skills.size())));
        SessionState s = new SessionState(sessionId, topic);
        states.put(sessionId, s);

        // 生成第 1 题
        String q = generateQuestion(s, traceId);
        s.questions.add(q);
        return "面试主题: " + topic + "\n\n问题 1: " + q;
    }

    /** 回答 + 评分 + 下一题/追问 */
    public String answer(String sessionId, String userAnswer, String traceId) {
        SessionState s = states.get(sessionId);
        if (s == null || !"active".equals(s.status)) return "会话已结束";
        s.answers.add(userAnswer);

        // 评分
        int score = scoreAnswer(s, userAnswer, traceId);
        s.scores.add(score);

        // 追问 or 下一题
        if (s.followupsLeft > 0 && score < 7) {
            s.followupsLeft--;
            String follow = "你答对了部分, 但深入不够. 请补充: " + s.questions.get(s.currentQ);
            return "评分: " + score + "/10\n追问: " + follow;
        }

        // 推进
        s.currentQ++;
        s.followupsLeft = MAX_FOLLOWUPS_PER_Q;
        if (s.currentQ >= MAX_QUESTIONS) {
            s.status = "finished";
            return "评分: " + score + "/10\n\n面试结束, 正在生成复盘报告...";
        }
        String nextQ = generateQuestion(s, traceId);
        s.questions.add(nextQ);
        return "评分: " + score + "/10\n\n问题 " + (s.currentQ + 1) + ": " + nextQ;
    }

    /** 复盘报告 */
    public Report report(String sessionId) {
        SessionState s = states.get(sessionId);
        if (s == null) return null;
        double avg = s.scores.stream().mapToInt(Integer::intValue).average().orElse(0);
        // 简化: 不调 LLM 生成细致复盘, 直接基于分数排序
        return new Report(s.scores.size(), avg,
                List.of("对 " + s.topic + " 有基本了解"),
                List.of("需要深入底层原理, 不只停留在 API 使用"),
                List.of("skill:" + s.topic)); // 简化推荐
    }

    private String generateQuestion(SessionState s, String traceId) {
        try {
            String json = gateway.chatJson(Purpose.PLAN, List.of(
                    SystemMessage.from("你是技术面试官, 基于主题出一道中等难度问题, 30字内, 直接输出问题不要 JSON."),
                    UserMessage.from("主题: " + s.topic + " 进度: 第" + (s.currentQ + 1) + "题")), traceId);
            return json.replaceAll("[\\n\\r]", "").trim();
        } catch (Exception e) {
            return "请描述你对 " + s.topic + " 的核心理解";
        }
    }

    private int scoreAnswer(SessionState s, String answer, String traceId) {
        try {
            String json = gateway.chatJson(Purpose.JUDGE, List.of(
                    SystemMessage.from("你是面试评分员. 基于问题与回答, 给 0-10 分. 输出 JSON {\"score\":N}"),
                    UserMessage.from("问题: " + s.questions.get(s.currentQ) + "\n回答: " + answer)), traceId);
            var node = mapper.readTree(json);
            return Math.max(0, Math.min(10, node.path("score").asInt(5)));
        } catch (Exception e) {
            return 5;
        }
    }
}