package com.tutor.coaching.interview;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Optional release gate for an authorized, protected human-gold file.
 * The normal test suite skips this class unless the CI job supplies the path.
 */
@EnabledIfSystemProperty(named = "interviewScoreGoldPath", matches = ".+")
class InterviewScoreEvalGateTest {
    @Test
    void protectedHumanGoldSetMustPassReleaseGate() throws Exception {
        String path = System.getProperty("interviewScoreGoldPath");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(Path.of(path).toFile());
        InterviewScoreEvalService.ReplayRequest request;
        if (root.isArray()) {
            List<InterviewScoreEvalService.ReplayCase> cases = mapper.convertValue(root,
                    new TypeReference<>() {});
            request = new InterviewScoreEvalService.ReplayRequest("protected-ci", cases);
        } else {
            request = mapper.treeToValue(root, InterviewScoreEvalService.ReplayRequest.class);
        }

        Map<String, Object> result = new InterviewScoreEvalService(mapper).replay(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> metrics = (Map<String, Object>) result.get("metrics");
        assertThat(metrics).containsEntry("releaseEligible", true);
    }
}
