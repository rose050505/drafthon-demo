package com.v2v.backend;

import static com.v2v.backend.Api.*;
import static org.springframework.http.HttpStatus.*;

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
    private static final String SELECT = """
        select p.id, p.startup_plan_id as "startupPlanId", p.title, p.interest,
               p.work_description as "workDescription", p.deliverable, p.period_weeks as "periodWeeks",
               p.academic_credit as "academicCredit", p.stipend_krw as "stipendKrw", p.availability, p.status,
               p.published_at as "publishedAt", a.display_name as "ownerName"
        from postings p join accounts a on a.id = p.owner_id
        """;
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
        return db.queryForList(SELECT + " where p.owner_id = ? order by p.created_at desc, p.id limit ? offset ?",
            owner, limit(limit), offset(offset));
    }

    Map<String, Object> visible(UUID id) {
        var row = first(db.queryForList(SELECT + " where p.id = ? and p.status in ('PUBLISHED', 'MATCHED')", id));
        row.remove("startupPlanId");
        return row;
    }

    Map<String, Object> owned(UUID owner, UUID id) {
        return first(db.queryForList(SELECT + " where p.id = ? and p.owner_id = ?", id, owner));
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
                                 availability, id, owner_id, startup_plan_id, status)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT')
            """, input.title().strip(), input.interest().strip(), input.workDescription().strip(), input.deliverable().strip(),
            input.periodWeeks(), optional(input.academicCredit()), input.stipendKrw(), input.availability().strip(), id, owner, plan);
        return owned(owner, id);
    }

    Map<String, Object> edit(UUID owner, UUID id, PostingController.Posting input) {
        var current = owned(owner, id);
        if (input.startupPlanId() != null && !input.startupPlanId().equals(current.get("startupPlanId"))) {
            throw invalid("공고에 연결된 창업 계획은 변경할 수 없습니다.");
        }
        int updated = db.update("""
            update postings set title = ?, interest = ?, work_description = ?, deliverable = ?, period_weeks = ?,
                academic_credit = ?, stipend_krw = ?, availability = ? where id = ? and owner_id = ? and status = 'DRAFT'
            """, input.title().strip(), input.interest().strip(), input.workDescription().strip(), input.deliverable().strip(),
            input.periodWeeks(), optional(input.academicCredit()), input.stipendKrw(), input.availability().strip(), id, owner);
        if (updated == 0) throw conflict("게시되기 전의 공고만 수정할 수 있습니다.");
        return owned(owner, id);
    }

    Map<String, Object> publish(UUID owner, UUID id) {
        owned(owner, id);
        int updated = db.update("""
            update postings set status = 'PUBLISHED', published_at = current_timestamp
            where id = ? and owner_id = ? and status = 'DRAFT'
            """, id, owner);
        var posting = owned(owner, id);
        if (updated == 0 && !"PUBLISHED".equals(posting.get("status"))) throw conflict("이미 모집이 종료된 공고입니다.");
        return posting;
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
@RequestMapping("/api")
class PostingController {
    private final Postings postings;

    PostingController(Postings postings) { this.postings = postings; }

    @GetMapping("/postings")
    List<Map<String, Object>> browse(@RequestParam(required = false) String keyword,
        @RequestParam(required = false) String interest, @RequestParam(defaultValue = "30") int limit,
        @RequestParam(defaultValue = "0") int offset) {
        var result = postings.browse(keyword, interest, limit, offset);
        result.forEach(row -> row.remove("startupPlanId"));
        return result;
    }

    @GetMapping("/postings/{id}")
    Map<String, Object> find(@PathVariable UUID id) { return postings.visible(id); }

    @GetMapping("/me/postings")
    List<Map<String, Object>> mine(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "30") int limit,
        @RequestParam(defaultValue = "0") int offset) {
        return postings.mine(actor.require(Role.FOUNDER, Role.OWNER), limit, offset);
    }

    @GetMapping("/me/postings/{id}")
    Map<String, Object> owned(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return postings.owned(actor.require(Role.FOUNDER, Role.OWNER), id);
    }

    @PostMapping("/postings")
    @ResponseStatus(CREATED)
    Map<String, Object> create(@AuthenticationPrincipal Actor actor, @Valid @RequestBody Posting input) {
        return postings.create(actor, input);
    }

    @PutMapping("/postings/{id}")
    Map<String, Object> edit(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody Posting input) {
        return postings.edit(actor.require(Role.FOUNDER, Role.OWNER), id, input);
    }

    @PostMapping("/postings/{id}/draft-generation")
    @ResponseStatus(ACCEPTED)
    Map<String, Object> draft(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return postings.draft(actor.require(Role.FOUNDER, Role.OWNER), id);
    }

    @PostMapping("/postings/{id}/publish")
    Map<String, Object> publish(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return postings.publish(actor.require(Role.FOUNDER, Role.OWNER), id);
    }

    @PostMapping("/postings/recommendations")
    @ResponseStatus(ACCEPTED)
    Map<String, Object> recommend(@AuthenticationPrincipal Actor actor, @Valid @RequestBody Recommendation input) {
        return postings.recommend(actor.require(Role.STUDENT), input);
    }

    @PostMapping("/owner/task-planning")
    @ResponseStatus(ACCEPTED)
    Map<String, Object> ownerPlanning(@AuthenticationPrincipal Actor actor, @Valid @RequestBody OwnerBrief input) {
        return postings.ownerPlanning(actor.require(Role.OWNER), input);
    }

    record Posting(UUID startupPlanId, @NotBlank @Size(max = 180) String title, @NotBlank @Size(max = 100) String interest,
        @NotBlank @Size(max = 6000) String workDescription, @NotBlank @Size(max = 1500) String deliverable,
        @NotNull @Min(1) @Max(52) Integer periodWeeks, @Size(max = 150) String academicCredit,
        @NotNull @PositiveOrZero Long stipendKrw, @NotBlank @Size(max = 1000) String availability) {}

    record Recommendation(@NotBlank @Size(max = 500) String interests, @NotBlank @Size(max = 1000) String availability) {}
    record OwnerBrief(@NotBlank @Size(max = 160) String industry, @NotBlank @Size(max = 3000) String problem,
        @NotBlank @Size(max = 2000) String goal) {}
}
