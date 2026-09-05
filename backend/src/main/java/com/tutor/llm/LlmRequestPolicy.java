package com.tutor.llm;

import com.tutor.config.LlmProperties;
import com.tutor.conversation.context.TokenBudget;
import com.tutor.contract.Purpose;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Request shaping policy: bounds, limits, timeout selection, and adaptive cost estimation. */
final class LlmRequestPolicy {
    private final LlmProperties properties;
    private final TokenBudget tokenBudget;
    /** EMA calibration compensates for differences between local and provider tokenizers. */
    private volatile double reserveCalibration = 1.2D;

    LlmRequestPolicy(LlmProperties properties, TokenBudget tokenBudget) {
        this.properties = properties;
        this.tokenBudget = tokenBudget;
    }

    LlmProperties.TokenLimits tokenLimits() {
        return properties.tokens() == null ? LlmProperties.TokenLimits.defaults() : properties.tokens();
    }

    String boundedText(String text, int maxTokens) {
        if (text == null || text.isBlank()) return "";
        return tokenBudget.truncate(text, maxTokens);
    }

    long estimateText(String text) {
        return Math.max(128, (text == null ? 0 : tokenBudget.count(text)) + 128);
    }

    long estimate(Purpose purpose, List<ChatMessage> messages,
                  BudgetPressureService budgetPressure) {
        int input = messages.stream().mapToInt(message -> tokenBudget.count(messageText(message))).sum();
        long raw = Math.max(256, input + outputLimit(purpose, budgetPressure) + 128L);
        return (long) Math.ceil(raw * reserveCalibration);
    }

    /** Update the estimate only after a provider reports a successful measured usage. */
    synchronized void calibrate(long estimated, long measured) {
        if (estimated <= 0 || measured <= 0) return;
        double ratio = Math.max(0.5D, Math.min(2.0D, (double) measured / estimated));
        reserveCalibration = Math.max(0.8D, Math.min(1.5D,
                0.9D * reserveCalibration + 0.1D * ratio));
    }

    List<ChatMessage> boundedMessages(Purpose purpose, List<ChatMessage> messages,
                                      BudgetPressureService budgetPressure) {
        if (messages == null || messages.isEmpty()) return List.of();
        List<ChatMessage> safe = messages.stream().filter(Objects::nonNull).toList();
        int max = inputLimit(purpose);
        int total = safe.stream().mapToInt(message -> tokenBudget.count(messageText(message))).sum();
        if (total <= max) return safe;
        if (safe.size() == 1) {
            return List.of(withText(safe.getFirst(), tokenBudget.truncate(
                    messageText(safe.getFirst()), max)));
        }

        int lastIndex = safe.size() - 1;
        boolean hasSystem = safe.getFirst() instanceof SystemMessage;
        int systemBudget = hasSystem
                ? Math.min(tokenBudget.count(messageText(safe.getFirst())), Math.max(1, max * 2 / 5))
                : 0;
        int finalBudget = hasSystem && lastIndex == 0 ? 0
                : Math.min(tokenBudget.count(messageText(safe.getLast())),
                Math.max(1, (int) Math.round(max * finalMessageShare(purpose))));
        finalBudget = Math.min(finalBudget, Math.max(1, max - systemBudget));
        int remaining = Math.max(0, max - systemBudget - finalBudget);
        List<ChatMessage> middle = new ArrayList<>();
        int middleStart = hasSystem ? 1 : 0;
        int middleEnd = lastIndex - (finalBudget > 0 ? 1 : 0);
        for (int i = middleEnd - 1; i >= middleStart && remaining > 0; i--) {
            String text = messageText(safe.get(i));
            int take = Math.min(tokenBudget.count(text), remaining);
            middle.add(0, withText(safe.get(i), tokenBudget.truncate(text, take)));
            remaining -= take;
        }

        List<ChatMessage> result = new ArrayList<>();
        if (hasSystem) {
            result.add(withText(safe.getFirst(), tokenBudget.truncate(
                    messageText(safe.getFirst()), systemBudget)));
        }
        result.addAll(middle);
        if (finalBudget > 0) {
            result.add(withText(safe.getLast(), tokenBudget.truncate(
                    messageText(safe.getLast()), finalBudget)));
        }
        return result;
    }

    int inputLimit(Purpose purpose) {
        return limitFor(purpose).inputTokens();
    }

    int outputLimit(Purpose purpose, BudgetPressureService budgetPressure) {
        int limit = limitFor(purpose).outputTokens();
        if (purpose == Purpose.CHAT && budgetPressure != null) {
            return Math.min(limit, budgetPressure.chatOutputCap(limit));
        }
        return limit;
    }

    int timeoutSeconds(Purpose purpose) {
        LlmProperties.Timeout timeout = properties.timeout();
        return switch (purpose) {
            case ROUTER -> timeout.routerSeconds();
            case SUMMARY -> timeout.summarySeconds();
            case EXPERT -> timeout.expertSeconds();
            default -> timeout.chatSeconds();
        };
    }

    int defaultMaxAttempts(Purpose purpose) {
        return switch (purpose) {
            case ROUTER, EXPERT, SUMMARY, JUDGE -> 1;
            default -> 2;
        };
    }

    long estimateRerank(String query, List<String> docs) {
        int queryLength = query == null ? 0 : query.length();
        int documentLength = docs == null ? 0 : docs.stream().filter(Objects::nonNull)
                .mapToInt(String::length).sum();
        return Math.max(128, (queryLength + documentLength) / 2 + 128);
    }

    private LlmProperties.PurposeLimit limitFor(Purpose purpose) {
        LlmProperties.TokenLimits limits = tokenLimits();
        return switch (purpose) {
            case ROUTER -> limits.router();
            case CHAT -> limits.chat();
            case EXPERT -> limits.expert();
            case SUMMARY -> limits.summary();
            case EXTRACT -> limits.extract();
            case JUDGE -> limits.judge();
            case PLAN -> limits.plan();
            default -> limits.chat();
        };
    }

    private double finalMessageShare(Purpose purpose) {
        return switch (purpose) {
            case CHAT -> 0.55D;
            case ROUTER -> 0.65D;
            default -> 0.75D;
        };
    }

    private String messageText(ChatMessage message) {
        if (message instanceof SystemMessage system) return system.text();
        if (message instanceof UserMessage user && user.hasSingleText()) return user.singleText();
        if (message instanceof AiMessage ai && ai.text() != null) return ai.text();
        return message.toString();
    }

    private ChatMessage withText(ChatMessage message, String text) {
        if (message instanceof SystemMessage) return SystemMessage.from(text);
        if (message instanceof UserMessage) return UserMessage.from(text);
        if (message instanceof AiMessage) return AiMessage.from(text);
        return message;
    }
}
