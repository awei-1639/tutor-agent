package com.tutor.agent.expert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.Intent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** 将路由模型的自评分数映射为可用于安全门槛的校准概率。 */
@Component
public class RoutingConfidenceCalibrator {
    private static final Logger log = LoggerFactory.getLogger(RoutingConfidenceCalibrator.class);

    private final boolean enabled;
    private final CalibrationModel model;
    private final String unavailableReason;

    @Autowired
    public RoutingConfidenceCalibrator(
            @Value("${tutor.routing.calibration.enabled:false}") boolean enabled,
            @Value("${tutor.routing.calibration.model-path:}") String modelPath,
            ResourceLoader resourceLoader,
            ObjectMapper mapper) {
        this.enabled = enabled;
        if (!enabled) {
            this.model = null;
            this.unavailableReason = "CALIBRATION_DISABLED";
            return;
        }
        LoadResult loaded = loadModel(modelPath, resourceLoader, mapper);
        this.model = loaded.model();
        this.unavailableReason = loaded.reason();
    }

    RoutingConfidenceCalibrator(boolean enabled, CalibrationModel model) {
        this.enabled = enabled;
        this.model = model;
        this.unavailableReason = !enabled ? "CALIBRATION_DISABLED"
                : model == null ? "CALIBRATION_MODEL_UNAVAILABLE" : null;
    }

    static RoutingConfidenceCalibrator forTest(CalibrationModel model) {
        return new RoutingConfidenceCalibrator(true, model);
    }

    /**
     * 只为结构完整且预测为越界的路由校准分数。
     * 领域内或已降级的结果不会被伪造出一个可跳过检索的概率。
     */
    public CalibrationResult calibrate(IntentRouter.RouteDecision decision) {
        if (decision == null
                || decision.scope() != IntentRouter.Scope.OUT_OF_SCOPE
                || decision.intent() != Intent.OUT_OF_SCOPE
                || decision.retrievalHint() != IntentRouter.RetrievalHint.NONE
                || !decision.subIntents().isEmpty()
                || decision.degraded()) {
            return CalibrationResult.notApplicable();
        }
        if (!enabled) return CalibrationResult.unavailable(unavailableReason);
        if (model == null) return CalibrationResult.unavailable(unavailableReason);

        return new CalibrationResult(model.predict(decision.confidence()), model.version(), false, List.of());
    }

    private static LoadResult loadModel(String modelPath, ResourceLoader resourceLoader, ObjectMapper mapper) {
        if (modelPath == null || modelPath.isBlank()) {
            return new LoadResult(null, "CALIBRATION_MODEL_NOT_CONFIGURED");
        }
        try {
            var resource = resourceLoader.getResource(modelPath.trim());
            if (!resource.exists()) {
                return new LoadResult(null, "CALIBRATION_MODEL_NOT_FOUND");
            }
            try (InputStream input = resource.getInputStream()) {
                JsonNode root = mapper.readTree(input);
                return new LoadResult(parseModel(root), null);
            }
        } catch (Exception e) {
            log.warn("路由置信度校准模型加载失败 path={} type={}", modelPath,
                    e.getClass().getSimpleName());
            return new LoadResult(null, "CALIBRATION_MODEL_INVALID");
        }
    }

    private static CalibrationModel parseModel(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("calibration model must be an object");
        }
        String version = root.path("version").asText("").trim();
        JsonNode pointsNode = root.get("points");
        if (version.isBlank() || pointsNode == null || !pointsNode.isArray()) {
            throw new IllegalArgumentException("calibration model requires version and points");
        }
        if (root.path("development_only").asBoolean(false)) {
            throw new IllegalArgumentException("development-only calibration model cannot be loaded");
        }
        List<CalibrationPoint> points = new ArrayList<>();
        pointsNode.forEach(point -> {
            if (point == null || !point.isObject()
                    || !point.has("raw") || !point.has("calibrated")
                    || !point.get("raw").isNumber() || !point.get("calibrated").isNumber()) {
                throw new IllegalArgumentException("invalid calibration point");
            }
            points.add(new CalibrationPoint(point.get("raw").asDouble(),
                    point.get("calibrated").asDouble()));
        });
        return new CalibrationModel(version, points);
    }

    private record LoadResult(CalibrationModel model, String reason) {
    }

    public record CalibrationPoint(double raw, double calibrated) {
        public CalibrationPoint {
            if (!Double.isFinite(raw) || raw < 0D || raw > 1D) {
                throw new IllegalArgumentException("raw calibration point must be between 0 and 1");
            }
            if (!Double.isFinite(calibrated) || calibrated < 0D || calibrated > 1D) {
                throw new IllegalArgumentException("calibrated point must be between 0 and 1");
            }
        }
    }

    public record CalibrationModel(String version, List<CalibrationPoint> points) {
        public CalibrationModel {
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException("calibration model version is required");
            }
            if (points == null || points.size() < 2) {
                throw new IllegalArgumentException("calibration model requires at least two points");
            }
            points = List.copyOf(points);
            for (int i = 1; i < points.size(); i++) {
                CalibrationPoint previous = points.get(i - 1);
                CalibrationPoint current = points.get(i);
                if (current.raw() <= previous.raw()) {
                    throw new IllegalArgumentException("raw calibration points must be strictly increasing");
                }
                if (current.calibrated() < previous.calibrated()) {
                    throw new IllegalArgumentException("calibrated points must be monotonic");
                }
            }
        }

        double predict(double raw) {
            if (!Double.isFinite(raw)) return 0D;
            if (raw <= points.get(0).raw()) return points.get(0).calibrated();
            int last = points.size() - 1;
            if (raw >= points.get(last).raw()) return points.get(last).calibrated();
            for (int i = 1; i < points.size(); i++) {
                CalibrationPoint right = points.get(i);
                if (raw <= right.raw()) {
                    CalibrationPoint left = points.get(i - 1);
                    double ratio = (raw - left.raw()) / (right.raw() - left.raw());
                    return left.calibrated() + ratio * (right.calibrated() - left.calibrated());
                }
            }
            return points.get(last).calibrated();
        }
    }

    public record CalibrationResult(Double confidence, String version, boolean degraded,
                                     List<String> reasonCodes) {
        public CalibrationResult {
            if (confidence != null && (!Double.isFinite(confidence)
                    || confidence < 0D || confidence > 1D)) {
                confidence = null;
                degraded = true;
            }
            version = version == null || version.isBlank() ? null : version;
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        }

        static CalibrationResult notApplicable() {
            return new CalibrationResult(null, null, false, List.of());
        }

        static CalibrationResult unavailable(String reason) {
            return new CalibrationResult(null, null, true,
                    List.of(reason == null || reason.isBlank() ? "CALIBRATION_UNAVAILABLE" : reason));
        }
    }
}
