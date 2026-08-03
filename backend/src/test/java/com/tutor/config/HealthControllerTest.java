package com.tutor.config;

import org.junit.jupiter.api.Test;
import org.neo4j.driver.Driver;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Driver neo4j = mock(Driver.class);
    private final HealthController controller = new HealthController(jdbc, neo4j);

    @Test
    void livenessDoesNotDependOnStorage() {
        assertThat(controller.live()).containsEntry("status", "up");
    }

    @Test
    void readinessReturnsOkWhenBothStoresWork() {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        assertThat(controller.ready().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void readinessNamesUnavailableDependencyWithoutLeakingError() {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenThrow(new IllegalStateException("secret connection detail"));
        doThrow(new IllegalStateException("secret bolt detail")).when(neo4j).verifyConnectivity();

        var result = controller.ready();
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result.getBody()).containsEntry("status", "not_ready");
        assertThat(result.getBody().get("unavailable")).asList().containsExactly("postgres", "neo4j");
    }
}
