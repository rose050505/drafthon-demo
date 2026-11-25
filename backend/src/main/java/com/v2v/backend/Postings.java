package com.v2v.backend;

import static com.v2v.backend.Api.*;
import static org.springframework.http.HttpStatus.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

@Service
class Postings {
    private static final String FIELDS = """
        select p.id, p.startup_plan_id as "startupPlanId", p.title, p.interest,
               p.work_description as "workDescription", p.deliverable, p.period_weeks as "periodWeeks",
               p.academic_credit as "academicCredit", p.stipend_krw as "stipendKrw", p.availability, p.status,
               p.published_at as "publishedAt", a.display_name as "ownerName", p.student_limit as "studentLimit",
               p.advisor_name as "advisorName", p.review_status as "reviewStatus",
               (select count(*) from posting_team_matches m where m.posting_id = p.id) as "matchedStudentCount"
        """;
    private static final String FROM = " from postings p join accounts a on a.id = p.owner_id ";
    private static final String SELECT = FIELDS + FROM;
    private static final String SELECT_OWNER = FIELDS + """
        , p.review_notes as "reviewNotes", p.readiness_score as "readinessScore", p.task_fit_score as "taskFitScore",
          p.review_opinion_count as "reviewOpinionCount", p.review_like_count as "reviewLikeCount",
          p.reviewed_at as "reviewedAt"
        """ + FROM;
    private final JdbcTemplate db;
    private final Startup startup;
    private final AiJobs ai;

    Postings(JdbcTemplate db, Startup startup, AiJobs ai) {
        this.db = db;
        this.startup = startup;
        this.ai = ai;
    }

    List<Map<String, Object>> browse(String keyword, String interest, int limit, int offset) {
        return db.queryForList(SELECT + """
            where p.status = 'PUBLISHED' and (lower(p.title) like ? escape '!' or lower(p.work_description) like ? escape '!')
                and lower(p.interest) like ? escape '!'
            order by p.published_at desc, p.id limit ? offset ?
            """, pattern(keyword), pattern(keyword), pattern(interest), limit(limit), offset(offset));
    }

    List<Map<String, Object>> mine(UUID owner, int limit, int offset) {
        return db.queryForList(SELECT_OWNER + " where p.owner_id = ? order by p.created_at desc, p.id limit ? offset ?",
            owner, limit(limit), offset(offset));
    }

    Map<String, Object> visible(UUID id) {
        var row = first(db.queryForList(SELECT + " where p.id = ? and p.status in ('PUBLISHED', 'MATCHED')", id));
        row.remove("startupPlanId");
        return row;
    }

    Map<String, Object> owned(UUID owner, UUID id) {
        return first(db.queryForList(SELECT_OWNER + " where p.id = ? and p.owner_id = ?", id, owner));
    }

    Map<String, Object> create(Actor actor, PostingController.Posting input) {
        UUID owner = actor.require(Role.FOUNDER, Role.OWNER);
        UUID plan = input.startupPlanId();
        if (actor.role() == Role.FOUNDER) {
            if (plan == null) throw invalid("선택을 완료한 창업 계획을 연결하세요.");
            Startup.requireSelection(startup.get(owner, plan));
        } else if (plan != null) {
            throw invalid("기존 사업자 공고에는 예비 창업 계획을 연결할 수 없습니다.");
        }
        UUID id = UUID.randomUUID();
        db.update("""
            insert into postings(title, interest, work_description, deliverable, period_weeks, academic_credit, stipend_krw,
                                 availability, id, owner_id, startup_plan_id, student_limit, advisor_name, status)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT')
            """, input.title().strip(), input.interest().strip(), input.workDescription().strip(), input.deliverable().strip(),
            input.periodWeeks(), optional(input.academicCredit()), input.stipendKrw(), input.availability().strip(), id, owner, plan,
            input.studentLimit() == null ? 4 : input.studentLimit(), optional(input.advisorName()));
        return owned(owner, id);
    }

    Map<String, Object> edit(UUID owner, UUID id, PostingController.Posting input) {
        var current = owned(owner, id);
        if (input.startupPlanId() != null && !input.startupPlanId().equals(current.get("startupPlanId"))) {
            throw invalid("공고에 연결된 창업 계획은 변경할 수 없습니다.");
        }
        int updated = db.update("""
            update postings set title = ?, interest = ?, work_description = ?, deliverable = ?, period_weeks = ?,
                academic_credit = ?, stipend_krw = ?, availability = ?,
                student_limit = coalesce(?, student_limit), advisor_name = coalesce(?, advisor_name)
            where id = ? and owner_id = ? and status = 'DRAFT' and review_status in ('NOT_SUBMITTED', 'NEXT_OPPORTUNITY')
            """, input.title().strip(), input.interest().strip(), input.workDescription().strip(), input.deliverable().strip(),
            input.periodWeeks(), optional(input.academicCredit()), input.stipendKrw(), input.availability().strip(),
            input.studentLimit(), optional(input.advisorName()), id, owner);
        if (updated == 0) throw conflict("심사 제출 전 또는 다음 기회를 받은 초안만 수정할 수 있습니다.");
        return owned(owner, id);
    }

    Map<String, Object> publish(UUID owner, UUID id) {
        owned(owner, id);
        int updated = db.update("""
            update postings set status = 'PUBLISHED', published_at = current_timestamp
            where id = ? and owner_id = ? and status = 'DRAFT' and review_status = 'SELECTED'
            """, id, owner);
        var posting = owned(owner, id);
        if (updated == 0 && !"PUBLISHED".equals(posting.get("status"))) {
            throw conflict("운영단 심사에서 선정된 초안만 게시할 수 있습니다.");
        }
        return posting;
    }

    Map<String, Object> setAdvisor(UUID owner, UUID id, String name) {
        int updated = db.update("update postings set advisor_name = ? where id = ? and owner_id = ?", name.strip(), id, owner);
        if (updated == 0) throw missing();
        return owned(owner, id);
    }

    Map<String, Object> draft(UUID owner, UUID id) {
        var current = owned(owner, id);
        if (!"DRAFT".equals(current.get("status"))) throw conflict("게시되기 전의 공고만 분석할 수 있습니다.");
        UUID plan = (UUID) current.remove("startupPlanId");
        var input = plan == null ? Map.of("brief", current) : Map.of("brief", current, "plan", startup.get(owner, plan));
        return ai.queue(owner, JobKind.POSTING_DRAFT, id, input);
    }

    Map<String, Object> recommend(UUID student, PostingController.Recommendation input) {
        var postings = browse(null, null, 100, 0);
        postings.forEach(row -> row.remove("startupPlanId"));
        return ai.queue(student, JobKind.POSTING_RECOMMENDATIONS, null,
            Map.of("interests", input.interests(), "availability", input.availability(), "postings", postings));
    }

    Map<String, Object> ownerPlanning(UUID owner, PostingController.OwnerBrief input) {
        return ai.queue(owner, JobKind.OWNER_TASK_PLANNING, null,
            Map.of("industry", input.industry(), "problem", input.problem(), "goal", input.goal()));
    }

    private static String pattern(String value) {
        if (value == null || value.isBlank()) return "%";
        if (value.length() > 160) throw invalid("검색어는 160자 이하여야 합니다.");
        return "%" + value.strip().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}

@RestController
@Tag(name = "공고·추천", description = "학생 프로젝트 공고 열람, 작성, 게시와 맞춤 추천.")
@RequestMapping("/api")
class PostingController {
    private final Postings postings;

    PostingController(Postings postings) { this.postings = postings; }

    @GetMapping("/postings")
    @Operation(summary = "모집 중인 공고 검색",
        description = "학생이 지원할 수 있는 공개 공고 목록을 조회합니다. `keyword`로 제목과 업무 내용, `interest`로 관심 분야를 검색합니다.")
    List<Map<String, Object>> browse(@RequestParam(required = false) String keyword,
        @RequestParam(required = false) String interest, @RequestParam(defaultValue = "30") int limit,
        @RequestParam(defaultValue = "0") int offset) {
        var result = postings.browse(keyword, interest, limit, offset);
        result.forEach(row -> row.remove("startupPlanId"));
        return result;
    }

    @GetMapping("/postings/{id}")
    @Operation(summary = "공개 공고 상세 조회",
        description = "공고 ID로 업무 설명, 결과물, 기간, 학점, 활동비 등을 조회합니다. 게시된 공고와 매칭된 공고를 열람할 수 있습니다.")
    Map<String, Object> find(@PathVariable UUID id) { return postings.visible(id); }

    @GetMapping("/me/postings")
    @Operation(summary = "내가 만든 공고 목록 조회",
        description = "예비 또는 기존 창업자가 작성한 공고를 조회합니다. 초안과 게시 상태, 매칭 여부를 확인하는 화면에 사용합니다.")
    List<Map<String, Object>> mine(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "30") int limit,
        @RequestParam(defaultValue = "0") int offset) {
        return postings.mine(actor.require(Role.FOUNDER, Role.OWNER), limit, offset);
    }

    @GetMapping("/me/postings/{id}")
    @Operation(summary = "내 공고 상세 조회",
        description = "공고 작성자가 공고 ID로 자기 공고의 내용과 상태를 조회합니다. 공개 전 초안도 조회할 수 있습니다.")
    Map<String, Object> owned(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return postings.owned(actor.require(Role.FOUNDER, Role.OWNER), id);
    }

    @PostMapping("/postings")
    @Operation(summary = "학생 모집 공고 초안 생성",
        description = "제목, 맡길 일, 제출할 결과물, 기간과 지원 조건을 입력해 초안을 만듭니다. 예비 창업자는 위치와 업종을 확정한 `startupPlanId`를 연결합니다.")
    @ResponseStatus(CREATED)
    Map<String, Object> create(@AuthenticationPrincipal Actor actor, @Valid @RequestBody Posting input) {
        return postings.create(actor, input);
    }

    @PutMapping("/postings/{id}")
    @Operation(summary = "게시 전 공고 수정",
        description = "심사 제출 전 또는 다음 기회를 받은 초안의 업무, 결과물과 활동 조건을 수정합니다. `studentLimit`은 1~4명입니다.")
    Map<String, Object> edit(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody Posting input) {
        return postings.edit(actor.require(Role.FOUNDER, Role.OWNER), id, input);
    }

    @PostMapping("/postings/{id}/draft-generation")
    @Operation(summary = "공고 초안 작성 AI 요청",
        description = "작성자가 초안의 내용을 바탕으로 AI에 문구 제안을 요청합니다. 결과는 작업 조회 API에서 받아 검토한 뒤 공고 수정 API로 저장합니다.")
    @ResponseStatus(ACCEPTED)
    Map<String, Object> draft(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return postings.draft(actor.require(Role.FOUNDER, Role.OWNER), id);
    }

    @PostMapping("/postings/{id}/publish")
    @Operation(summary = "공고 게시",
        description = "운영단 심사에서 선정된 초안을 학생에게 공개합니다. 게시된 공고는 공개 목록에 표시됩니다.")
    Map<String, Object> publish(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return postings.publish(actor.require(Role.FOUNDER, Role.OWNER), id);
    }

    @PutMapping("/postings/{id}/advisor")
    @Operation(summary = "프로젝트 지도교수 지정",
        description = "공고 작성자가 지도교수 이름을 등록합니다. 이 공고의 모든 학생 매칭에서 같은 지도교수가 표시됩니다.")
    Map<String, Object> advisor(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody Advisor input) {
        return postings.setAdvisor(actor.require(Role.FOUNDER, Role.OWNER), id, input.advisorName());
    }

    @PostMapping("/postings/recommendations")
    @Operation(summary = "학생 맞춤 공고 AI 추천 요청",
        description = "학생 역할로 관심 직무 `interests`와 활동 가능 시간 `availability`를 보내 모집 중인 공고에 대한 추천을 요청합니다. 결과는 반환된 작업 ID로 조회합니다.")
    @ResponseStatus(ACCEPTED)
    Map<String, Object> recommend(@AuthenticationPrincipal Actor actor, @Valid @RequestBody Recommendation input) {
        return postings.recommend(actor.require(Role.STUDENT), input);
    }

    @PostMapping("/owner/task-planning")
    @Operation(summary = "기존 사업자 과제 정리 AI 요청",
        description = "기존 사업자가 업종 `industry`, 해결하고 싶은 문제 `problem`, 목표 `goal`을 보냅니다. AI가 학생에게 맡길 과제와 우선순위를 제안하는 작업을 접수합니다.")
    @ResponseStatus(ACCEPTED)
    Map<String, Object> ownerPlanning(@AuthenticationPrincipal Actor actor, @Valid @RequestBody OwnerBrief input) {
        return postings.ownerPlanning(actor.require(Role.OWNER), input);
    }

    record Posting(UUID startupPlanId, @NotBlank @Size(max = 180) String title, @NotBlank @Size(max = 100) String interest,
        @NotBlank @Size(max = 6000) String workDescription, @NotBlank @Size(max = 1500) String deliverable,
        @NotNull @Min(1) @Max(52) Integer periodWeeks, @Size(max = 150) String academicCredit,
        @NotNull @PositiveOrZero Long stipendKrw, @NotBlank @Size(max = 1000) String availability,
        @Min(1) @Max(4) Integer studentLimit, @Size(max = 120) String advisorName) {}

    record Advisor(@NotBlank @Size(max = 120) String advisorName) {}
    record Recommendation(@NotBlank @Size(max = 500) String interests, @NotBlank @Size(max = 1000) String availability) {}
    record OwnerBrief(@NotBlank @Size(max = 160) String industry, @NotBlank @Size(max = 3000) String problem,
        @NotBlank @Size(max = 2000) String goal) {}
}
