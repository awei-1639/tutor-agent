package com.tutor.knowledge.retrieval.fusion;

import com.tutor.contract.Evidence;
import com.tutor.knowledge.retrieval.graph.GraphExpansionPolicy;
import com.tutor.knowledge.retrieval.graph.GraphStore;
import com.tutor.knowledge.retrieval.vector.VectorStore;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JobSkillAnswerPolicyTest {
    private static final String SKILL_SEEKING = "腾讯做对话系统的NLP算法工程师，得会啥技能啊？";
    private static final String JOB_ID = "job:nlp-algorithm-engineer-3";

    @Test
    void promotesRequiredSkillsInPlaceOfTheTopJobNode() {
        Evidence job = evidence(JOB_ID, "job", 0.82D);
        Evidence siblingJob = evidence("job:nlp-engineer-302", "job", 0.07D);
        List<Evidence> ranked = List.of(job, siblingJob);
        List<GraphStore.Neighbor> neighbors = List.of(
                requires(JOB_ID, "skill:rag", 0.9D),
                requires(JOB_ID, "skill:transformers", 0.95D),
                requires(JOB_ID, "skill:inactive", 0.99D, "inactive"),
                teaches("skill:transformers", "skill:rag", 1.0D));
        Map<String, VectorStore.VectorHit> hits = new HashMap<>();
        hits.put("skill:rag", new VectorStore.VectorHit("skill:rag", "skill", "RAG 检索增强", 0.5D));
        hits.put("skill:transformers", new VectorStore.VectorHit("skill:transformers", "skill", "Transformer 结构", 0.6D));

        List<Evidence> result = JobSkillAnswerPolicy.promoteRequiredSkills(ranked, neighbors, hits, SKILL_SEEKING);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).nodeId()).isEqualTo("skill:transformers");
        assertThat(result.get(1).nodeId()).isEqualTo("skill:rag");
        assertThat(result.get(0).score()).isEqualTo(0.82D);
        assertThat(result.get(0).chunkText()).isEqualTo("Transformer 结构");
        assertThat(result.get(0).graphPath()).contains("REQUIRES");
        assertThat(result).extracting(Evidence::nodeId).doesNotContain(JOB_ID);
        assertThat(result.get(2).nodeId()).isEqualTo("job:nlp-engineer-302");
    }

    @Test
    void leavesRankingUnchangedWhenQueryIsNotSkillSeeking() {
        Evidence job = evidence(JOB_ID, "job", 0.82D);
        List<GraphStore.Neighbor> neighbors = List.of(requires(JOB_ID, "skill:rag", 0.9D));

        List<Evidence> result = JobSkillAnswerPolicy.promoteRequiredSkills(
                List.of(job), neighbors, Map.of(), "推荐几个NLP算法岗的职位");

        assertThat(result).containsExactly(job);
    }

    @Test
    void leavesRankingUnchangedWhenTopNodeIsNotAJob() {
        Evidence skill = evidence("skill:transformers", "skill", 0.9D);
        List<GraphStore.Neighbor> neighbors = List.of(requires(JOB_ID, "skill:rag", 0.9D));

        List<Evidence> result = JobSkillAnswerPolicy.promoteRequiredSkills(
                List.of(skill), neighbors, Map.of(), SKILL_SEEKING);

        assertThat(result).containsExactly(skill);
    }

    @Test
    void leavesRankingUnchangedWhenTheTopJobHasNoRequiresNeighbors() {
        Evidence job = evidence(JOB_ID, "job", 0.82D);
        List<GraphStore.Neighbor> neighbors = List.of(teaches("skill:transformers", "skill:rag", 1.0D));

        List<Evidence> result = JobSkillAnswerPolicy.promoteRequiredSkills(
                List.of(job), neighbors, Map.of(), SKILL_SEEKING);

        assertThat(result).containsExactly(job);
    }

    @Test
    void fallsBackToNodeNameWhenNoVectorHitExistsForASkill() {
        Evidence job = evidence(JOB_ID, "job", 0.5D);
        List<GraphStore.Neighbor> neighbors = List.of(requires(JOB_ID, "skill:rag", 0.9D));

        List<Evidence> result = JobSkillAnswerPolicy.promoteRequiredSkills(
                List.of(job), neighbors, Map.of(), SKILL_SEEKING);

        assertThat(result.get(0).nodeId()).isEqualTo("skill:rag");
        assertThat(result.get(0).chunkText()).isEqualTo("rag");
        assertThat(result.get(0).sourceStatus()).isEqualTo(job.sourceStatus());
    }

    private static Evidence evidence(String nodeId, String type, double score) {
        return new Evidence(nodeId, type, nodeId + " 文本", score, null, null, "verified", null);
    }

    private static GraphStore.Neighbor requires(String jobId, String skillId, double confidence) {
        return requires(jobId, skillId, confidence, "active");
    }

    private static GraphStore.Neighbor requires(String jobId, String skillId, double confidence, String status) {
        return new GraphStore.Neighbor(jobId, "REQUIRES", skillId, skillId.replaceFirst("^skill:", ""),
                GraphExpansionPolicy.Direction.OUTGOING, confidence, "seed", status, "skill");
    }

    private static GraphStore.Neighbor teaches(String srcId, String dstId, double confidence) {
        return new GraphStore.Neighbor(srcId, "TEACHES", dstId, dstId,
                GraphExpansionPolicy.Direction.INCOMING, confidence, "seed", "active", "skill");
    }
}
