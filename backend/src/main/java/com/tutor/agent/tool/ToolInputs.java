package com.tutor.agent.tool;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.multipart.MultipartFile;

public final class ToolInputs {
    private ToolInputs() { }

    public record Empty() { }

    public record Retrieve(
            @NotBlank @Size(max = 4000) String query,
            @Min(1) @Max(20) Integer topK,
            @Pattern(regexp = "agentic|vector_only|fused|fused_rerank") @Size(max = 32) String mode
    ) { }

    public record ResumeUpload(@NotNull MultipartFile file) { }
}
