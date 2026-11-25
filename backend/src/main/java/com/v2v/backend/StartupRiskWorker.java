package com.v2v.backend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@ConditionalOnProperty(prefix = "v2v.startup-risk", name = "enabled", havingValue = "true")
class StartupRiskWorker {
    private static final Logger LOG = LoggerFactory.getLogger(StartupRiskWorker.class);
    private final AiJobs jobs;
    private final JsonMapper json;
    private final String python;
    private final Path script;

    StartupRiskWorker(AiJobs jobs, JsonMapper json, @Value("${v2v.startup-risk.python}") String python,
        @Value("${v2v.startup-risk.script}") Path script) {
        this.jobs = jobs;
        this.json = json;
        this.python = python;
        this.script = script.toAbsolutePath().normalize();
    }

    @Scheduled(fixedDelayString = "${v2v.startup-risk.poll-interval-ms:1500}",
        initialDelayString = "${v2v.startup-risk.poll-interval-ms:1500}")
    void processNext() {
        var job = jobs.claimStartupRisk();
        if (job == null) return;
        UUID id = (UUID) job.get("id");
        UUID leaseId = (UUID) job.get("leaseId");
        JsonNode result;
        var status = AiController.ResultStatus.COMPLETED;
        try {
            result = analyze(job.get("input"));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return; // The queue will make the job available again when the lease expires.
        } catch (IOException | JacksonException error) {
            LOG.warn("STARTUP_RISK {} could not complete ({})", id, error.getClass().getSimpleName());
            result = json.createObjectNode().put("code", "STARTUP_RISK_UNAVAILABLE")
                .put("message", "창업 위험 분석을 완료하지 못했습니다. 다시 요청해 주세요.");
            status = AiController.ResultStatus.FAILED;
        }
        jobs.finish(id, new AiController.Result(leaseId, status, result));
    }

    private JsonNode analyze(Object input) throws IOException, InterruptedException {
        Path directory = Files.createTempDirectory("v2v-startup-risk-");
        Path request = directory.resolve("request.json");
        Path response = directory.resolve("response.json");
        Process process = null;
        try {
            Files.writeString(request, json.writeValueAsString(input));
            process = new ProcessBuilder(python, "-E", "-B", script.toString())
                .redirectInput(request.toFile())
                .redirectOutput(response.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            if (!process.waitFor(25, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("Python analyzer did not complete");
            }
            if (Files.size(response) > 120_000) throw new IOException("Python analyzer result exceeds limit");
            var result = json.readTree(Files.readString(response));
            if (result == null || !result.isObject()) throw new IOException("Python analyzer did not return an object");
            return result;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(response);
            Files.deleteIfExists(request);
            Files.deleteIfExists(directory);
        }
    }
}
