package com.tutor.agent.expert;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则 facet 判定对照人工标注语料 ({@code evals/router_testset.json} v2, 30 条 / 每类 5 条)。
 *
 * <p>语料与口径都在仓库里, 这里硬编码副本是为了让单测自包含 (不依赖工作目录与网络);
 * 改动 {@code router_testset.json} 的 facet 标签时必须同步这里, 否则门禁会失效。
 */
class RetrievalFacetRulesTest {

    private static final Set<RoutingPolicy.RetrievalFacet> CAREER = Set.of(RoutingPolicy.RetrievalFacet.CAREER);
    private static final Set<RoutingPolicy.RetrievalFacet> LEARNING = Set.of(RoutingPolicy.RetrievalFacet.LEARNING);
    private static final Set<RoutingPolicy.RetrievalFacet> RESOURCE = Set.of(RoutingPolicy.RetrievalFacet.RESOURCE);
    private static final Set<RoutingPolicy.RetrievalFacet> CAREER_LEARNING =
            Set.of(RoutingPolicy.RetrievalFacet.CAREER, RoutingPolicy.RetrievalFacet.LEARNING);
    private static final Set<RoutingPolicy.RetrievalFacet> NONE = Set.of();

    private record Case(String question, Set<RoutingPolicy.RetrievalFacet> expected) { }

    private static List<Case> annotatedCorpus() {
        return List.of(
                // resume → career
                new Case("帮我看看简历里的项目经历怎么写更亮眼", CAREER),
                new Case("我的简历投大模型岗位老是没回音，是不是内容有问题", CAREER),
                new Case("应届生简历一页还是两页好？帮我优化下结构", CAREER),
                new Case("怎么在简历里体现我的RAG项目经验", CAREER),
                new Case("这个岗位JD和我的简历匹配度怎么样", CAREER),
                // interview → learning (话术类为空)
                new Case("给我出10道NLP算法工程师的笔试题", LEARNING),
                new Case("明天要面试了，帮我模拟一下大模型岗的面试", LEARNING),
                new Case("Transformer相关的高频面试题有哪些", LEARNING),
                new Case("面试官问我为什么转行，该怎么回答比较好", NONE),
                new Case("帮我准备一下算法岗的系统设计面试", LEARNING),
                // planning → learning
                new Case("帮我制定一个三个月转行AI的学习计划", LEARNING),
                new Case("每天只有2小时，怎么安排学习大模型开发", LEARNING),
                new Case("我该先学机器学习还是直接上手LangChain", LEARNING),
                new Case("零基础学Python要多久，给我排个课表", LEARNING),
                new Case("下个月开始准备秋招，学习节奏怎么定", LEARNING),
                // mixed → career + learning
                new Case("两周后面试NLP岗，帮我出题加复习规划，简历也顺便看看", CAREER_LEARNING),
                new Case("我想转行大模型方向：怎么学、简历怎么改、面试怎么准备", CAREER_LEARNING),
                new Case("秋招冲刺：出一套面试题，再帮我规划复习和简历重点", CAREER_LEARNING),
                new Case("帮我全面评估下现在离拿到算法offer还差什么", CAREER_LEARNING),
                new Case("既要改简历又要准备笔试，时间怎么分配，都帮我弄一下", CAREER_LEARNING),
                // chat: 纯概念为空, 岗位信息为 career, 资料本身为 resource
                new Case("什么是检索增强生成", NONE),
                new Case("大模型应用开发岗位现在市场行情怎么样", CAREER),
                new Case("LoRA微调和全参数微调有什么区别", NONE),
                new Case("杭州的NLP岗位多吗", CAREER),
                new Case("西瓜书适合入门看吗", RESOURCE),
                // out_of_scope → 空
                new Case("帮我写一篇朋友圈文案宣传我的奶茶店", NONE),
                new Case("今晚吃什么好，推荐几道家常菜", NONE),
                new Case("帮我翻译一段英文合同", NONE),
                new Case("明天北京天气怎么样", NONE),
                new Case("给我讲个笑话放松一下", NONE),
                // job_requirement 形态 (rag_testset 真实查询): 岗位主体 + 要求动词 → career
                // (gold 挂在岗位出边上; 2026-10-25 A/B 评测发现缺这条时该分片 hit@5 从 0.800 塌到 0.133)
                new Case("腾讯做对话系统的NLP算法工程师，得会啥技能啊？", CAREER_LEARNING),
                new Case("天眼科技招遥感图像CV工程师，需要掌握哪些技术？", CAREER_LEARNING),
                new Case("商汤科技的大模型应用开发，需要啥技能？", CAREER_LEARNING),
                new Case("移视科技的移动端优化CV岗，要会哪些技能？", CAREER_LEARNING),
                new Case("百度的强化学习算法实习生，要掌握哪些东西？", CAREER_LEARNING),
                // 开发者视角问法 (review 2026-10-25 对抗用例): 裸「开发」不是岗位信号,
                // 「开发一个X需要学什么」是学习诉求; 「Java后端开发」等复合词才是岗位主体
                new Case("我要自己开发一个RAG应用，需要学什么？", LEARNING),
                new Case("开发一个聊天机器人要学什么？", LEARNING),
                new Case("我想转开发，需要学什么？", LEARNING),
                new Case("帮我开发个脚本，要求能批量重命名", NONE),
                // 复合词「后端开发」仍应命中岗位判定
                new Case("远程数据科技的Java后端开发，要会啥技能？", CAREER_LEARNING)
        );
    }

    @Test
    void matchesHumanAnnotatedFacets() {
        List<Case> corpus = annotatedCorpus();
        List<String> mismatches = new ArrayList<>();
        for (Case each : corpus) {
            Set<RoutingPolicy.RetrievalFacet> actual = Set.copyOf(RetrievalFacetRules.classify(each.question()));
            if (!actual.equals(each.expected())) {
                mismatches.add(each.question() + " → 期望" + each.expected() + " 实际" + actual);
            }
        }
        double exactMatch = (corpus.size() - mismatches.size()) / (double) corpus.size();
        System.out.println("[facet-rules] exact-match=" + String.format("%.3f", exactMatch)
                + " (" + (corpus.size() - mismatches.size()) + "/" + corpus.size() + ")");
        mismatches.forEach(m -> System.out.println("[facet-rules] 差异: " + m));
        // 门禁与 evals/run_eval.mjs 的 router_facet_exact_match 对齐 (0.85)。
        assertThat(exactMatch).as("规则 facet 在人工标注语料上的精确匹配率").isGreaterThanOrEqualTo(0.85D);
    }

    @Test
    void returnsNoFacetsForBlankOrUnrelatedInput() {
        assertThat(RetrievalFacetRules.classify(null)).isEmpty();
        assertThat(RetrievalFacetRules.classify("   ")).isEmpty();
        assertThat(RetrievalFacetRules.classify("帮我看看天气")).isEmpty();
    }

    @Test
    void keepsCareerLearningOrderingStable() {
        assertThat(RetrievalFacetRules.classify("帮我改简历，再规划一下复习"))
                .containsExactly(RoutingPolicy.RetrievalFacet.LEARNING, RoutingPolicy.RetrievalFacet.CAREER);
    }
}
