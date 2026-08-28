package com.tutor.llm.structured;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ResumeExtractOutput(
        List<Education> education,
        List<Experience> experiences,
        List<Project> projects,
        List<String> skills,
        String summary
) {
    public record Education(String school, String degree, String major, String period) {
    }

    public record Experience(
            String company,
            String title,
            String period,
            List<String> highlights
    ) {
    }

    public record Project(
            String name,
            String role,
            String description,
            List<String> tech
    ) {
    }
}
