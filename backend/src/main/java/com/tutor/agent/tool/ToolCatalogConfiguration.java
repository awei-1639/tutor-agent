package com.tutor.agent.tool;

import com.tutor.coaching.interview.InterviewSkillEvidenceStore;
import com.tutor.coaching.plan.PlanService;
import com.tutor.contract.SideEffect;
import com.tutor.contract.ToolSpec;
import com.tutor.identity.profile.ProfileService;
import com.tutor.coaching.push.PushService;
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
                new ToolSpec("profile_snapshot", ToolInputs.Empty.class, Duration.ofSeconds(2), true, SideEffect.L0,
                        "读取用户画像快照（目标/偏好/掌握度），无参数"),
                Set.of("chat", "planner", "resume", "interview"),
                (input, context) -> profiles.snapshot(context.userId()));
    }

    @Bean
    ToolRegistration pushRunTool(PushService pushService) {
        return new ToolRegistration(
                new ToolSpec("push_run", ToolInputs.Empty.class, Duration.ofSeconds(30), false, SideEffect.L2,
                        "立即执行一次岗位匹配推送（副作用操作，需要用户确认）"),
                Set.of("scheduler"),
                (input, context) -> pushService.runForTool(context.userId()));
    }

    @Bean
    ToolRegistration retrievalTool(RetrievalToolService retrieval) {
        return new ToolRegistration(
                new ToolSpec("retrieve", ToolInputs.Retrieve.class, Duration.ofSeconds(30), true, SideEffect.L0,
                        "按查询检索知识库证据，参数 query（字符串）与 top_n（整数，可选）"),
                Set.of("eval", "chat", "planner", "resume", "interview"),
                (input, context) -> retrieval.retrieve((ToolInputs.Retrieve) input, context.traceId()));
    }

    @Bean
    ToolRegistration resumeUploadTool(ResumeService resumeService) {
        return new ToolRegistration(
                new ToolSpec("resume_upload", ToolInputs.ResumeUpload.class, Duration.ofSeconds(120), false, SideEffect.L1,
                        "上传并解析简历文件，参数 file（文件）"),
                Set.of("resume"),
                (input, context) -> {
                    var file = ((ToolInputs.ResumeUpload) input).file();
                    if (file.isEmpty()) throw new IllegalArgumentException("文件为空");
                    var result = resumeService.upload(context.userId(), file);
                    return java.util.Map.of("resume_id", result.resumeId(), "masked_pii_count", result.maskedPiiCount(),
                            "structured", result.structured());
                });
    }

    @Bean
    ToolRegistration planTodayTool(PlanService plans) {
        return new ToolRegistration(
                new ToolSpec("plan_today", ToolInputs.Empty.class, Duration.ofSeconds(3), true, SideEffect.L0,
                        "读取用户今天的学习计划任务（内容/类型/预计分钟数），无参数"),
                Set.of("chat"),
                (input, context) -> plans.todayTasks(context.userId()));
    }

    @Bean
    ToolRegistration interviewWeaknessTool(InterviewSkillEvidenceStore evidence) {
        return new ToolRegistration(
                new ToolSpec("interview_weakness", ToolInputs.Empty.class, Duration.ofSeconds(3), true, SideEffect.L0,
                        "读取最近模拟面试中平均得分偏低的薄弱技能清单，无参数"),
                Set.of("chat"),
                (input, context) -> evidence.recentWeakSkills(context.userId(), 7.0, 5));
    }

    @Bean
    ToolRegistration learningProgressTool(PlanService plans) {
        return new ToolRegistration(
                new ToolSpec("learning_progress", ToolInputs.Empty.class, Duration.ofSeconds(3), true, SideEffect.L0,
                        "读取最近 7 天计划完成度（done/total/完成率），无参数"),
                Set.of("chat"),
                (input, context) -> plans.learningProgress(context.userId()));
    }
}
