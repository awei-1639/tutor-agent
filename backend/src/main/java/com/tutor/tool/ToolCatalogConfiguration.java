package com.tutor.tool;

import com.tutor.contract.SideEffect;
import com.tutor.contract.ToolSpec;
import com.tutor.identity.profile.ProfileService;
import com.tutor.push.PushService;
import com.tutor.identity.resume.ResumeService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Set;

@Configuration
public class ToolCatalogConfiguration {
    @Bean
    ToolRegistration profileSnapshotTool(ProfileService profiles) {
        return new ToolRegistration(
                new ToolSpec("profile_snapshot", ToolInputs.Empty.class, Duration.ofSeconds(2), true, SideEffect.L0),
                Set.of("chat", "planner", "resume", "interview"),
                (input, context) -> profiles.snapshot(context.userId()));
    }

    @Bean
    ToolRegistration pushRunTool(PushService pushService) {
        return new ToolRegistration(
                new ToolSpec("push_run", ToolInputs.Empty.class, Duration.ofSeconds(30), false, SideEffect.L2),
                Set.of("scheduler"),
                (input, context) -> pushService.runForTool(context.userId()));
    }

    @Bean
    ToolRegistration retrievalTool(RetrievalToolService retrieval) {
        return new ToolRegistration(
                new ToolSpec("retrieve", ToolInputs.Retrieve.class, Duration.ofSeconds(30), true, SideEffect.L0),
                Set.of("eval", "chat", "planner", "resume", "interview"),
                (input, context) -> retrieval.retrieve((ToolInputs.Retrieve) input, context.traceId()));
    }

    @Bean
    ToolRegistration resumeUploadTool(ResumeService resumeService) {
        return new ToolRegistration(
                new ToolSpec("resume_upload", ToolInputs.ResumeUpload.class, Duration.ofSeconds(120), false, SideEffect.L1),
                Set.of("resume"),
                (input, context) -> {
                    var file = ((ToolInputs.ResumeUpload) input).file();
                    if (file.isEmpty()) throw new IllegalArgumentException("文件为空");
                    var result = resumeService.upload(context.userId(), file);
                    return java.util.Map.of("resume_id", result.resumeId(), "masked_pii_count", result.maskedPiiCount(),
                            "structured", result.structured());
                });
    }
}
