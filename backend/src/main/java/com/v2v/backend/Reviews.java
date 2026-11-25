package com.v2v.backend;

import static com.v2v.backend.Api.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
class Reviews {
    private static final String EVIDENCE = """
        (select count(*) from building_opinions o where o.building_id = p.review_building_id
            and (p.review_vacancy_id is null or o.vacancy_id = p.review_vacancy_id)) as "opinionCount",
        (select count(*) from building_opinions o join building_opinion_likes l on l.opinion_id = o.id
            where o.building_id = p.review_building_id
            and (p.review_vacancy_id is null or o.vacancy_id = p.review_vacancy_id)) as "likeCount"
        """;
    private final JdbcTemplate db;
    private final Postings postings;
    private final Startup startup;
    private final Catalog catalog;

    Reviews(JdbcTemplate db, Postings postings, Startup startup, Catalog catalog) {
        this.db = db;
        this.postings = postings;
        this.startup = startup;
        this.catalog = catalog;
    }

    @Transactional
    Map<String, Object> submit(UUID owner, UUID id, ReviewController.Target target) {
        UUID plan = (UUID) postings.owned(owner, id).get("startupPlanId");
        UUID building = target == null ? null : target.buildingId();
        UUID vacancy = target == null ? null : target.vacancyId();
        if (plan != null) {
            var selection = startup.get(owner, plan);
            building = (UUID) selection.get("selectedBuildingId");
            vacancy = (UUID) selection.get("selectedVacancyId");
        }
        if (vacancy != null) {
            UUID actualBuilding = (UUID) catalog.vacancy(vacancy).get("buildingId");
            if (building != null && !building.equals(actualBuilding)) throw invalid("해당 건물의 공실을 지정하세요.");
            building = actualBuilding;
        }
        if (building != null) catalog.building(building);
        int submitted = db.update("""
            update postings set review_status = 'PENDING', review_building_id = ?, review_vacancy_id = ?,
                review_requested_at = current_timestamp, reviewed_at = null, reviewer_id = null,
                readiness_score = null, task_fit_score = null, review_opinion_count = null,
                review_like_count = null, review_notes = null
            where id = ? and owner_id = ? and status = 'DRAFT' and review_status in ('NOT_SUBMITTED', 'NEXT_OPPORTUNITY')
            """, building, vacancy, id, owner);
        if (submitted == 0) throw conflict("심사 전이거나 다음 기회를 받은 초안만 제출할 수 있습니다.");
        return postings.owned(owner, id);
    }

    List<Map<String, Object>> pending(int limit, int offset) {
        return db.queryForList("""
            select p.id, p.title, p.interest, p.work_description as "workDescription", p.deliverable,
                p.period_weeks as "periodWeeks", p.availability, p.student_limit as "studentLimit",
                p.review_status as "reviewStatus", p.review_building_id as "buildingId", p.review_vacancy_id as "vacancyId",
                p.review_requested_at as "reviewRequestedAt", a.display_name as "ownerName",
                s.selected_industry as "selectedIndustry", s.business_idea as "businessIdea",
            """ + EVIDENCE + """
            from postings p join accounts a on p.owner_id = a.id left join startup_plans s on p.startup_plan_id = s.id
            where p.status = 'DRAFT' and p.review_status = 'PENDING'
            order by p.review_requested_at, p.id limit ? offset ?
            """, limit(limit), offset(offset));
    }

    @Transactional
    Map<String, Object> decide(UUID reviewer, UUID id, ReviewController.Result decision) {
        var target = first(db.queryForList("""
            select owner_id, review_status from postings where id = ? and status = 'DRAFT' for update
            """, id));
        if (!"PENDING".equals(target.get("review_status"))) throw conflict("심사 대기 중인 공고가 필요합니다.");
        var evidence = db.queryForMap("select " + EVIDENCE + " from postings p where id = ?", id);
        db.update("""
            update postings set review_status = ?, reviewer_id = ?, reviewed_at = current_timestamp,
                readiness_score = ?, task_fit_score = ?, review_opinion_count = ?, review_like_count = ?, review_notes = ?
            where id = ?
            """, decision.status().name(), reviewer, decision.readinessScore(), decision.taskFitScore(),
            evidence.get("opinionCount"), evidence.get("likeCount"), optional(decision.notes()), id);
        return postings.owned((UUID) target.get("owner_id"), id);
    }
}

@RestController
@Tag(name = "운영단 심사", description = "공고 제출, 운영단 선정과 심사 결과 확인.")
@RequestMapping("/api")
class ReviewController {
    private final Reviews reviews;

    ReviewController(Reviews reviews) {
        this.reviews = reviews;
    }

    @PostMapping("/postings/{id}/review")
    @Operation(summary = "공고를 운영단 심사에 제출",
        description = "창업자가 초안을 제출합니다. 예비 창업자는 확정한 위치를 사용하며 기존 사장님은 건물 또는 공실 ID를 연결할 수 있습니다.")
    Map<String, Object> submit(@AuthenticationPrincipal Actor actor, @PathVariable UUID id,
        @RequestBody(required = false) Target target) {
        return reviews.submit(actor.require(Role.FOUNDER, Role.OWNER), id, target);
    }

    @GetMapping("/operations/posting-reviews")
    @Operation(summary = "운영단 심사 대기 목록 조회",
        description = "운영단이 공고의 준비 내용, 과제와 해당 위치의 주민 의견 수 및 공감 수를 조회합니다.")
    List<Map<String, Object>> pending(@AuthenticationPrincipal Actor actor,
        @RequestParam(defaultValue = "30") int limit, @RequestParam(defaultValue = "0") int offset) {
        actor.require(Role.OPERATOR);
        return reviews.pending(limit, offset);
    }

    @PutMapping("/operations/postings/{id}/review")
    @Operation(summary = "운영단 심사 결과 확정",
        description = "준비 정도와 과제 적합성을 0~100점으로 입력하고 선정 또는 다음 기회를 결정합니다. 결정 시점의 주민 의견 및 공감 수도 저장됩니다.")
    Map<String, Object> decide(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody Result result) {
        return reviews.decide(actor.require(Role.OPERATOR), id, result);
    }

    record Target(UUID buildingId, UUID vacancyId) {}
    record Result(@NotNull Decision status, @NotNull @Min(0) @Max(100) Integer readinessScore,
        @NotNull @Min(0) @Max(100) Integer taskFitScore, @Size(max = 1500) String notes) {}
    enum Decision { SELECTED, NEXT_OPPORTUNITY }
}
