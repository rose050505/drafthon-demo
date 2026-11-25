package com.v2v.backend;

import static com.v2v.backend.Api.*;
import static org.springframework.http.HttpStatus.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

enum JobKind {
    BUILDING_DEMAND, STARTUP_RISK, STARTUP_LOCATION, STARTUP_INDUSTRY, STARTUP_COMMUNITY,
    STARTUP_FAQ, STARTUP_CHAT, OWNER_TASK_PLANNING, POSTING_DRAFT, POSTING_RECOMMENDATIONS, APPLICATION_QUESTIONS
}

@Service
class AiJobs {
    private final JdbcTemplate db;
    private final JsonMapper json;

    AiJobs(JdbcTemplate db, JsonMapper json) {
        this.db = db;
        this.json = json;
    }

    Map<String, Object> queue(UUID actor, JobKind kind, UUID scope, Object input) {
        UUID id = UUID.randomUUID();
        db.update("insert into ai_jobs(id, account_id, kind, scope_id, input_data, status) values (?, ?, ?, ?, ?, 'QUEUED')",
            id, actor, kind.name(), scope, write(input));
        return Map.of("id", id, "kind", kind.name(), "status", "QUEUED");
    }

    Map<String, Object> owned(UUID id, UUID account) {
        return parse(first(db.queryForList("""
            select id, kind, scope_id as "scopeId", status, result_data as result,
                   created_at as "createdAt", finished_at as "finishedAt"
            from ai_jobs where id = ? and account_id = ?
            """, id, account)), "result");
    }

    List<Map<String, Object>> demand(UUID building) {
        return db.queryForList("""
            select id, kind, status, result_data as result, finished_at as "finishedAt"
            from ai_jobs where kind = 'BUILDING_DEMAND' and scope_id = ? and status = 'COMPLETED'
            order by finished_at desc, id desc limit 1
            """, building).stream().map(row -> parse(row, "result")).toList();
    }

    @Transactional
    Map<String, Object> claim() {
        var candidates = db.queryForList("""
            select id from ai_jobs where status = 'QUEUED'
                or (status = 'RUNNING' and lease_expires_at <= current_timestamp)
            order by created_at, id limit 25
            """, UUID.class);
        for (UUID id : candidates) {
            UUID lease = UUID.randomUUID();
            int count = db.update("""
                update ai_jobs set status = 'RUNNING', lease_id = ?, lease_expires_at = ?
                where id = ? and (status = 'QUEUED' or (status = 'RUNNING' and lease_expires_at <= current_timestamp))
                """, lease, Accounts.now().plusMinutes(5), id);
            if (count == 1) {
                return parse(first(db.queryForList("""
                    select id, kind, scope_id as "scopeId", input_data as input,
                           lease_id as "leaseId", lease_expires_at as "leaseExpiresAt"
                    from ai_jobs where id = ?
                    """, id)), "input");
            }
        }
        return null;
    }

    void finish(UUID id, AiController.Result input) {
        JobKind kind = JobKind.valueOf(first(db.queryForList("""
            select kind from ai_jobs where id = ? and lease_id = ?
            """, String.class, id, input.leaseId())));
        if (!input.result().isObject()) throw invalid("result는 JSON 객체여야 합니다.");
        if (kind == JobKind.APPLICATION_QUESTIONS && input.status() == AiController.ResultStatus.COMPLETED) {
            questions(input.result());
        }
        String result = write(input.result());
        if (result.length() > 120_000) throw invalid("AI 결과가 허용된 크기를 초과했습니다.");
        int updated = db.update("""
            update ai_jobs set result_data = ?, status = ?, finished_at = current_timestamp
            where id = ? and lease_id = ? and status = 'RUNNING' and lease_expires_at > current_timestamp
            """, result, input.status().name(), id, input.leaseId());
        if (updated == 0 && db.queryForList("""
            select id from ai_jobs where id = ? and lease_id = ? and status = ? and result_data = ?
            """, UUID.class, id, input.leaseId(), input.status().name(), result).isEmpty()) {
            throw conflict("작업이 이미 종료되었거나 할당 시간이 지났습니다.");
        }
    }

    List<String> applicationQuestions(UUID id, UUID student, UUID posting) {
        String value = first(db.queryForList("""
            select result_data from ai_jobs where id = ? and account_id = ? and scope_id = ?
                and status = 'COMPLETED' and kind = 'APPLICATION_QUESTIONS'
            """, String.class, id, student, posting));
        return questions(read(value));
    }

    private static List<String> questions(JsonNode result) {
        var questions = result.path("questions");
        if (!questions.isArray() || questions.size() != 2) throw invalid("AI 지원 질문은 두 개여야 합니다.");
        String first = questions.get(0).asText("");
        String second = questions.get(1).asText("");
        if (!questions.get(0).isString() || !questions.get(1).isString() || first.isBlank() || second.isBlank()
            || first.length() > 1000 || second.length() > 1000) {
            throw invalid("두 지원 질문은 각각 1~1000자여야 합니다.");
        }
        return List.of(first, second);
    }

    String write(Object value) { return json.writeValueAsString(value); }

    JsonNode read(String value) { return json.readTree(value); }

    private Map<String, Object> parse(Map<String, Object> row, String key) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        String value = (String) row.get(key);
        result.put(key, value == null ? null : read(value));
        return result;
    }
}

@RestController
@Tag(name = "AI 작업", description = "사용자의 분석 결과 조회와 AI 워커의 작업 처리.")
@RequestMapping("/api")
class AiController {
    private final AiJobs jobs;
    private final V2vProperties config;

    AiController(AiJobs jobs, V2vProperties config) {
        this.jobs = jobs;
        this.config = config;
    }

    @GetMapping("/ai-jobs/{id}")
    @Operation(summary = "내 AI 작업 상태와 결과 조회",
        description = "분석이나 추천 API에서 받은 작업 ID로 본인 작업의 상태와 결과를 조회합니다. `QUEUED` 또는 `RUNNING`이면 처리 중이고, `COMPLETED`면 `result`를 사용합니다.")
    Map<String, Object> read(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return jobs.owned(id, actor.id());
    }

    @PostMapping("/integrations/ai/jobs/claim")
    @Operation(summary = "AI 워커가 대기 작업 가져오기",
        description = "AI 워커 토큰으로 대기 중인 작업 하나를 가져옵니다. 작업 종류, 입력 데이터, `leaseId`와 만료 시각을 반환합니다. 처리할 작업이 없으면 HTTP 204입니다.")
    ResponseEntity<Map<String, Object>> claim(@RequestHeader(name = "X-V2V-AI-Token", required = false) String token) {
        integrationToken(config.aiToken(), token);
        var job = jobs.claim();
        return job == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(job);
    }

    @PutMapping("/integrations/ai/jobs/{id}/result")
    @Operation(summary = "AI 워커 처리 결과 저장",
        description = "작업을 가져올 때 받은 `leaseId`, 상태 `COMPLETED` 또는 `FAILED`, JSON 객체 `result`를 보냅니다. 작업 할당 시간 안에 제출해야 합니다.")
    @ResponseStatus(NO_CONTENT)
    void finish(@RequestHeader(name = "X-V2V-AI-Token", required = false) String token, @PathVariable UUID id,
        @Valid @RequestBody Result result) {
        integrationToken(config.aiToken(), token);
        jobs.finish(id, result);
    }

    enum ResultStatus { COMPLETED, FAILED }
    record Result(@NotNull UUID leaseId, @NotNull ResultStatus status, @NotNull JsonNode result) {}
}
