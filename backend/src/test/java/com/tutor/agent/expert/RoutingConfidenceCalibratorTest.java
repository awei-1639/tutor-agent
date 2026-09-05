package com.tutor.agent.expert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tutor.contract.Intent;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingConfidenceCalibratorTest {
    private final RoutingConfidenceCalibrator.CalibrationModel model =
            new RoutingConfidenceCalibrator.CalibrationModel("test-v1", List.of(
                    new RoutingConfidenceCalibrator.CalibrationPoint(0D, 0.05D),
                    new RoutingConfidenceCalibrator.CalibrationPoint(0.5D, 0.60D),
                    new RoutingConfidenceCalibrator.CalibrationPoint(1D, 0.99D)));

    @Test
    void interpolatesCalibratedProbabilityForValidOutOfScopeDecision() {
        var calibrator = RoutingConfidenceCalibrator.forTest(model);
        var decision = new IntentRouter.RouteDecision(
                IntentRouter.Scope.OUT_OF_SCOPE, Intent.OUT_OF_SCOPE, List.of(), List.of(),
                IntentRouter.RetrievalHint.NONE, 0.75D, null, List.of(), false);

        var result = calibrator.calibrate(decision);

        assertThat(result.confidence()).isCloseTo(0.795D,
                org.assertj.core.data.Offset.offset(0.000001D));
        assertThat(result.version()).isEqualTo("test-v1");
        assertThat(result.degraded()).isFalse();
    }

    @Test
    void disabledCalibrationFailsClosedWithoutProducingConfidence() {
        var calibrator = new RoutingConfidenceCalibrator(false, null);
        var decision = new IntentRouter.RouteDecision(
                IntentRouter.Scope.OUT_OF_SCOPE, Intent.OUT_OF_SCOPE, List.of(), List.of(),
                IntentRouter.RetrievalHint.NONE, 0.99D, null, List.of(), false);

        var result = calibrator.calibrate(decision);

        assertThat(result.confidence()).isNull();
        assertThat(result.degraded()).isTrue();
        assertThat(result.reasonCodes()).containsExactly("CALIBRATION_DISABLED");
    }

    @Test
    void doesNotCalibrateInScopeOrDegradedDecisions() {
        var calibrator = RoutingConfidenceCalibrator.forTest(model);
        var inScope = new IntentRouter.RouteDecision(
                IntentRouter.Scope.IN_SCOPE, Intent.CHAT, List.of(), List.of(),
                IntentRouter.RetrievalHint.SINGLE, 0.99D, null, List.of(), false);
        var degraded = new IntentRouter.RouteDecision(
                IntentRouter.Scope.OUT_OF_SCOPE, Intent.OUT_OF_SCOPE, List.of(), List.of(),
                IntentRouter.RetrievalHint.NONE, 0.99D, null, List.of("INVALID"), true);

        assertThat(calibrator.calibrate(inScope).confidence()).isNull();
        assertThat(calibrator.calibrate(degraded).reasonCodes()).isEmpty();
        assertThat(calibrator.calibrate(degraded).degraded()).isFalse();
    }

    @Test
    void rejectsNonMonotonicModel() {
        assertThatThrownBy(() -> new RoutingConfidenceCalibrator.CalibrationModel("bad", List.of(
                new RoutingConfidenceCalibrator.CalibrationPoint(0.5D, 0.8D),
                new RoutingConfidenceCalibrator.CalibrationPoint(0.9D, 0.7D))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("monotonic");
    }

    @Test
    void loadsVersionedModelFromResource() {
        var calibrator = new RoutingConfidenceCalibrator(true,
                "classpath:routing/isotonic-test.json", new DefaultResourceLoader(), new ObjectMapper());
        var decision = new IntentRouter.RouteDecision(
                IntentRouter.Scope.OUT_OF_SCOPE, Intent.OUT_OF_SCOPE, List.of(), List.of(),
                IntentRouter.RetrievalHint.NONE, 0.75D, null, List.of(), false);

        var result = calibrator.calibrate(decision);

        assertThat(result.version()).isEqualTo("test-resource-v1");
        assertThat(result.confidence()).isCloseTo(0.795D,
                org.assertj.core.data.Offset.offset(0.000001D));
    }

    @Test
    void refusesDevelopmentOnlyArtifact() {
        var calibrator = new RoutingConfidenceCalibrator(true,
                "classpath:routing/isotonic-development-only.json",
                new DefaultResourceLoader(), new ObjectMapper());
        var decision = new IntentRouter.RouteDecision(
                IntentRouter.Scope.OUT_OF_SCOPE, Intent.OUT_OF_SCOPE, List.of(), List.of(),
                IntentRouter.RetrievalHint.NONE, 0.99D, null, List.of(), false);

        var result = calibrator.calibrate(decision);

        assertThat(result.confidence()).isNull();
        assertThat(result.reasonCodes()).containsExactly("CALIBRATION_MODEL_INVALID");
        assertThat(result.degraded()).isTrue();
    }
}
