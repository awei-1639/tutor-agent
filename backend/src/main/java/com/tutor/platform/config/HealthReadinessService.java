package com.tutor.platform.config;

import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Checks required backing stores for the readiness probe. */
@Service
public class HealthReadinessService {
    private static final Logger log = LoggerFactory.getLogger(HealthReadinessService.class);
    /** 单依赖探测上限: PG 宕机时 jdbc 调用会阻塞在池等待 (默认 ~30s), 探针必须远快于它给出 503。 */
    private static final long PROBE_TIMEOUT_MS = 2_000;
    private final JdbcTemplate jdbc;
    private final Driver neo4j;
    private final ExecutorService probeExecutor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "readiness-probe");
        t.setDaemon(true);
        return t;
    });

    public HealthReadinessService(JdbcTemplate jdbc, Driver neo4j) {
        this.jdbc = jdbc;
        this.neo4j = neo4j;
    }

    public List<String> unavailableDependencies() {
        List<String> unavailable = new ArrayList<>();
        unavailable.addAll(timedCheck("postgres", () -> jdbc.queryForObject("SELECT 1", Integer.class)));
        unavailable.addAll(timedCheck("neo4j", () -> {
            neo4j.verifyConnectivity();
            return null;
        }));
        return List.copyOf(unavailable);
    }

    /** 探测超时按不可用上报; 被放弃的探测线程会自行在池超时后结束, 不拖住探针响应。 */
    private List<String> timedCheck(String name, java.util.function.Supplier<?> check) {
        try {
            CompletableFuture.supplyAsync(check::get, probeExecutor)
                    .get(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return List.of();
        } catch (TimeoutException e) {
            log.warn("readiness: {} 探测 {}ms 未返回, 按不可用处理", name, PROBE_TIMEOUT_MS);
            return List.of(name);
        } catch (ExecutionException e) {
            log.warn("readiness: {} unavailable: {}", name, String.valueOf(e.getCause().getMessage()));
            return List.of(name);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of(name);
        }
    }
}
