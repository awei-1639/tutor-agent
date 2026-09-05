package com.tutor.expert;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ClarifyDetectingHandlerTest {

    static class Collected implements Aggregator.AggregateEvents {
        final List<String> tokens = new ArrayList<>();
        final AtomicReference<String> clarify = new AtomicReference<>();
        boolean completed;
        boolean clarifiedFlag;

        @Override public void onToken(String token) { tokens.add(token); }
        @Override public void onClarify(String question) { clarify.set(question); }
        @Override public void onComplete(String fullText, boolean clarified) { completed = true; clarifiedFlag = clarified; }
        @Override public void onError(Throwable error) { throw new AssertionError(error); }
    }

    private com.tutor.llm.LlmStreamResult completeResult() { return new com.tutor.llm.LlmStreamResult("test", 0, 0, false); }

    @Test
    void clarifyPrefixSplitAcrossTokensIsDetectedAndNotForwarded() {
        Collected c = new Collected();
        Aggregator.ClarifyDetectingHandler h = new Aggregator.ClarifyDetectingHandler(c);
        h.onToken("CLA");
        h.onToken("RIFY: 你的目标岗位具体是什么方向?");
        h.onComplete(completeResult());
        assertThat(c.tokens).isEmpty(); // 澄清模式下不透传token
        assertThat(c.clarify.get()).isEqualTo("你的目标岗位具体是什么方向?");
        assertThat(c.clarifiedFlag).isTrue();
    }

    @Test
    void normalAnswerForwardsBufferedHeadIntact() {
        Collected c = new Collected();
        Aggregator.ClarifyDetectingHandler h = new Aggregator.ClarifyDetectingHandler(c);
        h.onToken("C"); // 与CLARIFY前缀部分重合, 会先被缓冲
        h.onToken("ache优化是");
        h.onToken("这样的[S1]");
        h.onComplete(completeResult());
        assertThat(String.join("", c.tokens)).isEqualTo("Cache优化是这样的[S1]"); // 缓冲头部无丢失
        assertThat(c.clarify.get()).isNull();
        assertThat(c.clarifiedFlag).isFalse();
    }

    @Test
    void veryShortNonClarifyOutputStillForwardedOnComplete() {
        Collected c = new Collected();
        Aggregator.ClarifyDetectingHandler h = new Aggregator.ClarifyDetectingHandler(c);
        h.onToken("CL"); // 流结束时仍未攒够前缀长度
        h.onComplete(completeResult());
        assertThat(String.join("", c.tokens)).isEqualTo("CL");
        assertThat(c.clarifiedFlag).isFalse();
    }
}
