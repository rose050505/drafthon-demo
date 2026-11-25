package com.v2v.backend;

import static com.v2v.backend.Api.*;
import static org.springframework.http.HttpStatus.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
class Applications {
    private static final String APPLICATION = """
        select x.id, x.posting_id as "postingId", p.title, x.status, x.answers_json as answers,
            x.submitted_at as "submittedAt"
        from applications x join postings p on p.id = x.posting_id
        """;
    private static final String MATCH = """
        select m.id, m.posting_id as "postingId", p.title, p.interest, p.deliverable, p.period_weeks as "periodWeeks",
               p.academic_credit as "academicCredit", p.stipend_krw as "stipendKrw", m.application_id as "applicationId",
               m.status, m.handover_url as "handoverUrl", m.handover_notes as "handoverNotes", m.created_at as "createdAt",
               m.started_at as "startedAt", m.completed_at as "completedAt"
        from matches m join postings p on p.id = m.posting_id
        """;
    private final JdbcTemplate db;
    private final Postings postings;
    private final Students students;
    private final AiJobs ai;

    Applications(JdbcTemplate db, Postings postings, Students students, AiJobs ai) {
        this.db = db;
        this.postings = postings;
        this.students = students;
        this.ai = ai;
    }

    Map<String, Object> questions(UUID student, UUID posting) {
        var target = postings.visible(posting);
        requirePublished((String) target.get("status"));
        var profile = students.get(student);
        return ai.queue(student, JobKind.APPLICATION_QUESTIONS, posting,
            Map.of("posting", target, "interests", profile.get("interests"), "availability", profile.get("availability")));
    }

    @Transactional
    Map<String, Object> apply(UUID student, UUID posting, ApplicationController.Application input) {
        lockPosting(posting, null);
        if (!students.eligible(student)) throw conflict("학생 프로필과 재학증명서를 등록하세요.");
        List<String> questions = ai.applicationQuestions(input.questionJobId(), student, posting);
        List<Map<String, String>> answers = List.of(
            Map.of("question", questions.get(0), "answer", input.answers().get(0).strip()),
            Map.of("question", questions.get(1), "answer", input.answers().get(1).strip()));
        UUID id = UUID.randomUUID();
        db.update("""
            insert into applications(id, posting_id, student_id, question_job_id, answers_json, status)
            values (?, ?, ?, ?, ?, 'SUBMITTED')
            """, id, posting, student, input.questionJobId(), ai.write(answers));
        return parse(first(db.queryForList(APPLICATION + " where x.id = ?", id)));
    }

    List<Map<String, Object>> mine(UUID student, int limit, int offset) {
        return db.queryForList(APPLICATION + " where x.student_id = ? order by x.submitted_at desc, x.id limit ? offset ?",
            student, limit(limit), offset(offset)).stream().map(this::parse).toList();
    }

    List<Map<String, Object>> received(UUID owner, UUID posting, String major, Integer year, int limit, int offset) {
        postings.owned(owner, posting);
        String majorFilter = optional(major);
        if (majorFilter != null && majorFilter.length() > 120) throw invalid("전공은 120자 이하여야 합니다.");
        if (year != null && (year < 1 || year > 8)) throw invalid("학년 범위를 확인하세요.");
        return db.queryForList("""
            select x.id, x.status, x.answers_json as answers, x.submitted_at as "submittedAt",
                   a.display_name as "studentName", s.university, s.major, s.academic_year as "academicYear",
                   s.interests, s.availability, s.advisor, s.linked_course as "linkedCourse"
            from applications x join student_profiles s on x.student_id = s.account_id
            join accounts a on a.id = x.student_id
            where x.posting_id = ? and (? = false or lower(s.major) = lower(?))
                  and (? = false or s.academic_year = ?)
            order by x.submitted_at, x.id limit ? offset ?
            """, posting, majorFilter != null, majorFilter, year != null, year, limit(limit), offset(offset)).stream()
            .map(this::parse).toList();
    }

    @Transactional
    Map<String, Object> select(UUID owner, UUID posting, UUID application) {
        lockPosting(posting, owner);
        UUID student = first(db.queryForList("""
            select student_id from applications where id = ? and posting_id = ? and status = 'SUBMITTED'
            """, UUID.class, application, posting));
        UUID match = UUID.randomUUID();
        db.update("""
            insert into matches(id, posting_id, application_id, student_id, status) values (?, ?, ?, ?, 'MATCHED')
            """, match, posting, application, student);
        db.update("update applications set status = 'SELECTED' where id = ?", application);
        db.update("update applications set status = 'NOT_SELECTED' where posting_id = ? and id <> ?", posting, application);
        db.update("update postings set status = 'MATCHED' where id = ?", posting);
        return match(owner, match, false);
    }

    Map<String, Object> match(UUID account, UUID id, boolean isStudent) {
        return first(db.queryForList(MATCH + (isStudent
            ? " where m.id = ? and m.student_id = ?"
            : " where m.id = ? and p.owner_id = ?"), id, account));
    }

    List<Map<String, Object>> matches(UUID account, boolean isStudent, boolean completedOnly, int limit, int offset) {
        String condition = isStudent ? " where m.student_id = ?" : " where p.owner_id = ?";
        if (completedOnly) condition += " and m.status = 'COMPLETED'";
        return db.queryForList(MATCH + condition + " order by m.created_at desc, m.id limit ? offset ?",
            account, limit(limit), offset(offset));
    }

    Map<String, Object> start(UUID owner, UUID match) {
        match(owner, match, false);
        int changed = db.update("""
            update matches set status = 'IN_PROGRESS', started_at = current_timestamp
            where id = ? and status = 'MATCHED'
            """, match);
        var result = match(owner, match, false);
        if (changed == 0 && !"IN_PROGRESS".equals(result.get("status"))) throw conflict("매칭된 프로젝트만 시작할 수 있습니다.");
        return result;
    }

    Map<String, Object> complete(UUID owner, UUID match, ApplicationController.Handover input) {
        match(owner, match, false);
        String url = optional(input.handoverUrl());
        if (url != null) {
            try {
                URI uri = URI.create(url);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                    throw invalid("결과물 링크에는 HTTPS 주소를 입력하세요.");
                }
            } catch (IllegalArgumentException exception) {
                throw invalid("결과물 링크 형식을 확인하세요.");
            }
        }
        int changed = db.update("""
            update matches set status = 'COMPLETED', handover_url = ?, handover_notes = ?, completed_at = current_timestamp
            where id = ? and status = 'IN_PROGRESS'
            """, url, input.handoverNotes().strip(), match);
        if (changed == 0) throw conflict("진행 중인 프로젝트만 완료할 수 있습니다.");
        return match(owner, match, false);
    }

    private void lockPosting(UUID posting, UUID owner) {
        String query = "select status from postings where id = ?";
        String status = owner == null
            ? first(db.queryForList(query + " for update", String.class, posting))
            : first(db.queryForList(query + " and owner_id = ? for update", String.class, posting, owner));
        requirePublished(status);
    }

    private static void requirePublished(String status) {
        if (!"PUBLISHED".equals(status)) throw conflict("모집 중인 공고에서만 가능합니다.");
    }

    private Map<String, Object> parse(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("answers", ai.read((String) row.get("answers")));
        return result;
    }
}

@RestController
@RequestMapping("/api")
class ApplicationController {
    private final Applications applications;

    ApplicationController(Applications applications) { this.applications = applications; }

    @PostMapping("/postings/{id}/application-questions")
    @ResponseStatus(ACCEPTED)
    Map<String, Object> questions(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return applications.questions(actor.require(Role.STUDENT), id);
    }

    @PostMapping("/postings/{id}/applications")
    @ResponseStatus(CREATED)
    Map<String, Object> apply(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody Application input) {
        return applications.apply(actor.require(Role.STUDENT), id, input);
    }

    @GetMapping("/me/applications")
    List<Map<String, Object>> mine(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "30") int limit,
        @RequestParam(defaultValue = "0") int offset) {
        return applications.mine(actor.require(Role.STUDENT), limit, offset);
    }

    @GetMapping("/postings/{id}/applications")
    List<Map<String, Object>> received(@AuthenticationPrincipal Actor actor, @PathVariable UUID id,
        @RequestParam(required = false) String major, @RequestParam(required = false) Integer academicYear,
        @RequestParam(defaultValue = "30") int limit, @RequestParam(defaultValue = "0") int offset) {
        return applications.received(actor.require(Role.FOUNDER, Role.OWNER), id, major, academicYear, limit, offset);
    }

    @PostMapping("/postings/{id}/applications/{applicationId}/match")
    @ResponseStatus(CREATED)
    Map<String, Object> select(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @PathVariable UUID applicationId) {
        return applications.select(actor.require(Role.FOUNDER, Role.OWNER), id, applicationId);
    }

    @GetMapping("/me/matches")
    List<Map<String, Object>> matches(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "30") int limit,
        @RequestParam(defaultValue = "0") int offset) {
        return applications.matches(actor.require(Role.FOUNDER, Role.OWNER, Role.STUDENT), actor.role() == Role.STUDENT, false, limit, offset);
    }

    @GetMapping("/matches/{id}")
    Map<String, Object> match(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return applications.match(actor.require(Role.FOUNDER, Role.OWNER, Role.STUDENT), id, actor.role() == Role.STUDENT);
    }

    @PostMapping("/matches/{id}/start")
    Map<String, Object> start(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return applications.start(actor.require(Role.FOUNDER, Role.OWNER), id);
    }

    @PostMapping("/matches/{id}/complete")
    Map<String, Object> complete(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody Handover input) {
        return applications.complete(actor.require(Role.FOUNDER, Role.OWNER), id, input);
    }

    @GetMapping("/students/me/career-cards")
    List<Map<String, Object>> career(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "30") int limit,
        @RequestParam(defaultValue = "0") int offset) {
        return applications.matches(actor.require(Role.STUDENT), true, true, limit, offset);
    }

    record Application(@NotNull UUID questionJobId, @NotNull @Size(min = 2, max = 2) List<@NotBlank @Size(max = 2500) String> answers) {}
    record Handover(@Size(max = 1200) String handoverUrl, @NotBlank @Size(max = 2000) String handoverNotes) {}
}
