package com.tutor.agent.expert;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 检索 facet 的本地规则判定 (零 LLM 调用)。
 *
 * <p>口径完全照搬 {@code docs/facet-annotation-criteria.md}: facet 不是「这个请求属于什么话题」,
 * 而是「这次检索要不要扩某类图关系」的执行指令 —— 因此可以由文本信号直接判定, 不必每次都问模型。
 * 映射关系见 {@code GraphExpansionPolicy.forFacets}: career → 岗位出边 (REQUIRES/LEADS_TO),
 * learning → 技能依赖入边 (PREREQUISITE/TEACHES), resource → 资料入边 (TEACHES)。
 *
 * <p>判不出的情况一律返回空: 按标注规则 5「怀疑时给窄的那个」, 空 facet 只是不做图扩展,
 * 而多给一个 facet 会把无关候选挤进 TopK (FusedRetriever 有 per-source 配额)。
 *
 * <p>适用范围: 目前只用于工具循环内的检索 ({@code RetrievalToolService}) —— 那里只需要 facet,
 * 意图/澄清/越界都由主流程的路由决策负责, 再为每条子查询付一次完整路由调用是纯浪费。
 */
public final class RetrievalFacetRules {

    private RetrievalFacetRules() {
    }

    /** 纯概念解释与定义对比: 靠文本片段就能答好, 图关系帮不上忙 (标注规则 4)。 */
    private static final Pattern CONCEPT = Pattern.compile(
            "什么是|是啥|什么叫|是什么意思|是什么意思|有何区别|有什么区别|区别在哪|差异在哪|怎么理解|如何理解",
            Pattern.CASE_INSENSITIVE);

    /** 沟通话术: 落在"怎么说"上, 图谱里没有任何关系能支撑它。 */
    private static final Pattern SOFT_SKILL = Pattern.compile(
            "面试官问|怎么回答|该怎么回答|如何回答|怎么表达|如何表达|该怎么说|话术|怎么开口|怎么跟.*说",
            Pattern.CASE_INSENSITIVE);

    /** 问题主体就是资料本身: 单给 resource, 不叠加 learning (标注规则 3)。 */
    private static final Pattern RESOURCE_SUBJECT = Pattern.compile(
            "(这本书|这门课|这个课|这套课|这门课程|这份资料|这些资料|这套题|这个题库)"
                    + "|(适合(入门|新手|初学者|零基础|我看吗|我看么|自学))"
                    + "|(推荐.*(书|课程|课|资料|教材|视频|教程|题库))"
                    + "|((书|课程|资料|题库|教程|教材|讲义).*(怎么样|好不好|适合|值得|有用吗|怎么看|先看哪))"
                    // 资源问法补全 (multi_hop 诊断 2026-10-30): "有啥好书推荐/求文档教程/实战项目可以练手"
                    // 此前落进 LEARNING, 图加权会把前置技能块挤到资源 gold 之前。
                    + "|((有啥|有什么|求|来点|推荐).{0,10}(好书|书单|书|课程|资料|教材|教程|文档|题库|视频))"
                    + "|(实战项目.{0,8}(练手|推荐|上手))",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RESOURCE = Pattern.compile(
            "书|课程|资料|题库|教程|教材|视频课|公开课|讲义|文献|论文|博客|专栏",
            Pattern.CASE_INSENSITIVE);

    /** 岗位侧: 要的是「岗位需要什么 / 我够不够」这类岗位出边。 */
    private static final Pattern CAREER = Pattern.compile(
            "简历|岗位|JD|招聘|投递|offer|求职|内推|薪资|待遇|职位|招人|上岸|岗位要求|匹配度|"
                    + "市场行情|需求量|岗位多吗|好找吗|前景|转正|实习机会|面试机会",
            Pattern.CASE_INSENSITIVE);

    /**
     * 岗位主体 + 要求动词的联合判定。「XX工程师要会啥」这类真实提问不带上面任何 career 词,
     * 但 gold 恰恰挂在岗位出边上 (REQUIRES/LEADS_TO)——只给 learning 会让图扩展走错边,
     * job_requirement 分片 hit@5 因此从 0.800 塌到 0.133 (A/B 评测 2026-10-25)。
     * 要求动词是必要条件: 「NLP算法工程师的笔试题」只有主体没有诉求, 仍是 learning。
     *
     * <p>「开发」只以复合词形态参与 (后端/前端/应用/算法…开发): 裸词会把「自己开发一个RAG应用
     * 需要学什么」这类开发者视角的学习问法误判为岗位诉求 (review 2026-10-25 对抗用例)。
     */
    private static final Pattern JOB_TITLE = Pattern.compile(
            "工程师|分析师|架构师|实习生|科学家|研究员|经理|总监|岗|"
                    + "(后端|前端|应用|算法|软件|服务端|全栈|移动|大数据)开发",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern JOB_REQUIREMENT = Pattern.compile(
            "得会|要会|需要|都得|都要|会什么|什么技能|哪些技能|哪些技术|什么能力|哪些能力|什么技术|掌握哪些|掌握什么|要求|"
                    + "得懂|要懂|懂哪些|需要啥|要啥|会啥|懂啥|掌握啥|学什么|要学啥",
            Pattern.CASE_INSENSITIVE);

    /** 技能侧: 要的是知识点结构与先后依赖这类技能入边。「学什么」是纯学习诉求, 不构成岗位信号。 */
    private static final Pattern LEARNING = Pattern.compile(
            "学习|先学|怎么学|学什么|怎么入门|学习计划|学习路径|路线图|规划|课表|排期|节奏|前置|基础|入门|"
                    +             "多久|知识点|考点|笔试|面试|真题|面经|复习|备考|技能|算法题|手撕|"
                    + "刷题|项目实战|练手|掌握|精通|还差什么|缺什么|差在哪|"
                    // 前置问法的口语变体 (multi_hop 诊断 2026-10-30): "先会/先懂/先补/提前看"
                    // 与 "完全不懂/从哪儿开始" 在评测集 80 条里出现 10+ 次, 缺失导致图扩展没开。
                    + "先会|先懂|先补|提前看|从哪儿开始|从哪里开始|完全不懂|打基础|预备|先准备|要会啥|得会啥",
            Pattern.CASE_INSENSITIVE);

    /** 岗位诉求里夹着技能差距评估: 要答"还差什么"必须拉技能依赖, 因此补 learning。 */
    private static final Pattern SKILL_GAP_WITH_CAREER = Pattern.compile(
            "还差什么|差什么|缺什么|差在哪|需要具备|需要掌握|要掌握|够不够|达到什么水平",
            Pattern.CASE_INSENSITIVE);

    /**
     * 按文本信号判定检索 facet。判不出信号时返回空列表 (不做图扩展)。
     * 返回顺序固定为枚举序 (LEARNING, CAREER, RESOURCE), 便于断言与比对。
     *
     * <p>CONCEPT 让位 (multi_hop 诊断 2026-10-30): "正则化技术是啥？零基础想学，先得会哪些东西？"
     * 这类混合意图会被「是啥」整体拦截成空 facet, 图扩展没开。学习/岗位信号在场时, 说明用户要的
     * 不只是定义——此时 CONCEPT 不再抑制; 纯定义问法 (无任何学习/岗位信号) 仍维持空 facet。
     */
    public static List<RoutingPolicy.RetrievalFacet> classify(String query) {
        if (query == null || query.isBlank()) return List.of();
        String text = query.strip();
        if (SOFT_SKILL.matcher(text).find()) {
            return List.of();
        }
        if (RESOURCE_SUBJECT.matcher(text).find()) {
            // 问题主体就是资料本身: 单给 resource, 不叠加 learning (标注规则 3), 优先级最高。
            return List.of(RoutingPolicy.RetrievalFacet.RESOURCE);
        }
        boolean career = CAREER.matcher(text).find()
                || (JOB_TITLE.matcher(text).find() && JOB_REQUIREMENT.matcher(text).find());
        boolean learning = LEARNING.matcher(text).find()
                || (career && SKILL_GAP_WITH_CAREER.matcher(text).find());
        if (!learning && CONCEPT.matcher(text).find()) {
            return List.of();
        }
        Set<RoutingPolicy.RetrievalFacet> facets = EnumSet.noneOf(RoutingPolicy.RetrievalFacet.class);
        if (career) {
            facets.add(RoutingPolicy.RetrievalFacet.CAREER);
        }
        if (learning) {
            facets.add(RoutingPolicy.RetrievalFacet.LEARNING);
        }
        // learning 已含 TEACHES (权重略低), 只有在没有 learning 语义时才单给 resource。
        if (!learning && RESOURCE.matcher(text).find()) {
            facets.add(RoutingPolicy.RetrievalFacet.RESOURCE);
        }
        return List.copyOf(facets);
    }
}
