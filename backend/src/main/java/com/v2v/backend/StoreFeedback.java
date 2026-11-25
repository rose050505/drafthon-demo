package com.v2v.backend;

import static com.v2v.backend.Api.*;
import static org.springframework.http.HttpStatus.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
class StoreFeedback {
    private final JdbcTemplate db;
    private final Stores stores;
    private final ResidentAnswers answers;
    private final AiJobs ai;

    StoreFeedback(JdbcTemplate db, Stores stores, ResidentAnswers answers, AiJobs ai) {
        this.db = db;
        this.stores = stores;
        this.answers = answers;
        this.ai = ai;
    }

    @Transactional
    Map<String, Object> save(UUID account, UUID answer, StoreFeedbackController.Review review) {
        var visible = answers.visible(answer);
        if ("PREPARING".equals(visible.get("status"))) throw conflict("판매가 시작된 답변에 후기를 남길 수 있습니다.");
        if (db.queryForList("select account_id from answer_visits where answer_id = ? and account_id = ? for update",
            UUID.class, answer, account).isEmpty()) {
            throw error(FORBIDDEN, "방문 표시를 남긴 계정만 후기를 작성할 수 있습니다.");
        }
        if (db.update("""
            update answer_reviews set rating = ?, comment_text = ?, updated_at = current_timestamp
            where answer_id = ? and account_id = ?
            """, review.rating(), review.comment().strip(), answer, account) == 0) {
            db.update("insert into answer_reviews(answer_id, account_id, rating, comment_text) values (?, ?, ?, ?)",
                answer, account, review.rating(), review.comment().strip());
        }
        return first(db.queryForList("""
            select rating, comment_text as "comment", updated_at as "updatedAt"
            from answer_reviews where answer_id = ? and account_id = ?
            """, answer, account));
    }

    List<Map<String, Object>> list(UUID answer, int size, int start) {
        answers.visible(answer);
        return db.queryForList("""
            select rating, comment_text as "comment", updated_at as "updatedAt"
            from answer_reviews where answer_id = ? order by updated_at desc, account_id limit ? offset ?
            """, answer, limit(size), offset(start));
    }

    Map<String, Object> analyze(UUID owner, UUID store, UUID answer) {
        var profile = stores.owned(owner, store);
        if (answer != null && !store.equals(answers.visible(answer).get("storeId"))) throw missing();
        var reviews = db.queryForList("""
            select a.id as "answerId", m.name as "menuName", w.desired_business as "desiredBusiness",
                   r.rating, r.comment_text as "comment", r.updated_at as "updatedAt"
            from answer_reviews r join resident_request_answers a on a.id = r.answer_id
            join store_menus m on m.id = a.menu_id join building_opinions w on w.id = a.opinion_id
            where a.store_id = ? and not a.hidden and (? = false or a.id = ?)
            order by r.updated_at desc, r.answer_id, r.account_id limit 50
            """, store, answer != null, answer);
        if (reviews.isEmpty()) throw conflict("분석할 이용 후기가 없습니다.");
        return ai.queue(owner, JobKind.STORE_REVIEW_ANALYSIS, store, Map.of("storeName", profile.name(), "reviews", reviews));
    }
}

@RestController
@Tag(name = "이용 후기", description = "이용자가 남긴 후기와 사장님의 AI 후기 분석 요청입니다.")
@RequestMapping("/api")
class StoreFeedbackController {
    private final StoreFeedback feedback;

    StoreFeedbackController(StoreFeedback feedback) {
        this.feedback = feedback;
    }

    @GetMapping("/answers/{id}/reviews")
    @Operation(summary = "가게 답변의 이용 후기 조회", description = "방문 표시를 한 이용자가 작성한 공개 후기를 최신순으로 반환합니다.")
    List<Map<String, Object>> list(@PathVariable UUID id, @RequestParam(defaultValue = "30") int limit,
        @RequestParam(defaultValue = "0") int offset) {
        return feedback.list(id, limit, offset);
    }

    @PutMapping("/answers/{id}/reviews/me")
    @Operation(summary = "내 이용 후기 저장", description = "방문 표시를 한 계정이 평점과 후기를 작성하거나 수정합니다.")
    Map<String, Object> save(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody Review review) {
        return feedback.save(actor.id(), id, review);
    }

    @PostMapping("/stores/{id}/review-analysis")
    @ResponseStatus(ACCEPTED)
    @Operation(summary = "AI 이용 후기 분석 요청", description = "해당 가게의 최신 후기 최대 50건을 분석하도록 요청합니다. "
        + "특정 메뉴 답변만 분석할 때 answerId를 보냅니다.")
    Map<String, Object> analyze(@AuthenticationPrincipal Actor actor, @PathVariable UUID id,
        @RequestParam(required = false) UUID answerId) {
        return feedback.analyze(actor.require(Role.OWNER), id, answerId);
    }

    record Review(@NotNull @Min(1) @Max(5) Integer rating, @NotBlank @Size(max = 1500) String comment) {}
}
