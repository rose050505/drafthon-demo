package com.v2v.backend;

import static com.v2v.backend.Api.*;
import static org.springframework.http.HttpStatus.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
class ResidentAnswers {
    private static final int NEAR_METERS = 150;
    private static final int MAX_METERS = 300;
    private static final Pattern DIRECT_CONTACT = Pattern.compile("(?iu)https?://|www\\.|open\\.kakao\\.com|오픈채팅|010[ .-]?\\d{4}[ .-]?\\d{4}");
    private static final String WISH_DETAILS = """
        select o.id, o.building_id as "buildingId", o.vacancy_id as "vacancyId", o.desired_business as "desiredBusiness",
               o.category, o.reason, o.created_at as "createdAt", b.road_address as "roadAddress",
               b.longitude, b.latitude,
               (select count(*) from building_opinion_likes l where l.opinion_id = o.id) as "likeCount",
               (select count(*) from resident_request_answers a where a.opinion_id = o.id and not a.hidden) as "answerCount"
        from building_opinions o join buildings b on b.id = o.building_id
        """;
    private static final String PUBLIC_ANSWER = """
        select a.id, a.opinion_id as "wishId", a.store_id as "storeId", a.menu_id as "menuId", a.message, a.status,
               a.created_at as "createdAt", a.updated_at as "updatedAt", s.name as "storeName",
               s.category as "storeCategory", s.road_address as "roadAddress", s.longitude, s.latitude,
               m.name as "menuName", (select count(*) from answer_visits v where v.answer_id = a.id) as "visitCount"
        from resident_request_answers a join stores s on s.id = a.store_id join store_menus m on m.id = a.menu_id
        """;
    private final JdbcTemplate db;
    private final Stores stores;
    private final AiJobs ai;

    ResidentAnswers(JdbcTemplate db, Stores stores, AiJobs ai) {
        this.db = db;
        this.stores = stores;
        this.ai = ai;
    }

    List<Map<String, Object>> nearby(Stores.Store store, int size) {
        limit(size);
        var latitudeWindow = new BigDecimal("0.003");
        var candidates = db.queryForList(WISH_DETAILS + " where b.latitude between ? and ?",
            store.latitude().subtract(latitudeWindow), store.latitude().add(latitudeWindow));
        var within = new ArrayList<NearbyWish>();
        for (var wish : candidates) {
            double meters = distance(store, wish);
            if (meters > MAX_METERS) continue;
            wish.put("distanceMeters", Math.round(meters));
            within.add(new NearbyWish(wish, meters, store.category().equalsIgnoreCase((String) wish.get("category"))));
        }
        var near = within.stream().filter(wish -> wish.meters() <= NEAR_METERS).sorted(Comparator
            .comparing(NearbyWish::related).reversed()
            .thenComparing(Comparator.comparingLong(NearbyWish::likes).reversed())
            .thenComparingDouble(NearbyWish::meters)
            .thenComparing(wish -> (UUID) wish.data().get("id"))).limit(size).map(NearbyWish::data).toList();
        return near.isEmpty() ? within.stream().sorted(Comparator.comparingDouble(NearbyWish::meters)
            .thenComparing(wish -> (UUID) wish.data().get("id"))).limit(Math.min(size, 3)).map(NearbyWish::data).toList() : near;
    }

    private record NearbyWish(Map<String, Object> data, double meters, boolean related) {
        long likes() { return ((Number) data.get("likeCount")).longValue(); }
    }

    Map<String, Object> themes(UUID owner, UUID storeId) {
        var store = stores.owned(owner, storeId);
        var wishes = nearby(store, 12);
        if (wishes.size() < 2) throw conflict("요약할 근처 주민 요청이 두 건 이상 필요합니다.");
        return ai.queue(owner, JobKind.STORE_WISH_THEMES, storeId,
            Map.of("store", stores.profile(owner), "requests", wishes));
    }

    Map<String, Object> draft(UUID owner, UUID storeId, UUID wishId) {
        var wish = availableWish(stores.owned(owner, storeId), wishId);
        return ai.queue(owner, JobKind.STORE_ANSWER_DRAFT, wishId, Map.of("store", stores.profile(owner), "request", wish));
    }

    @Transactional
    Map<String, Object> publish(UUID owner, UUID wishId, AnswerController.NewAnswer input) {
        var store = stores.owned(owner, input.storeId());
        availableWish(store, wishId);
        String message = cleanMessage(input.message());
        if ((input.menuId() == null) == (optional(input.menuName()) == null)) {
            throw invalid("기존 메뉴 ID 또는 새 메뉴 이름 한 가지를 입력하세요.");
        }
        UUID menu = input.menuId() == null ? stores.ensureMenu(store.id(), input.menuName().strip()) : input.menuId();
        stores.requireMenu(store.id(), menu);
        db.update("update store_menus set active = true where id = ?", menu);
        UUID answer = UUID.randomUUID();
        db.update("""
            insert into resident_request_answers(id, opinion_id, store_id, menu_id, message, status)
            values (?, ?, ?, ?, ?, 'PREPARING')
            """, answer, wishId, store.id(), menu, message);
        notifyInterested(owner, wishId, answer, "ANSWER_POSTED");
        return visible(answer);
    }

    @Transactional
    Map<String, Object> edit(UUID owner, UUID answer, AnswerController.Change input) {
        var old = first(db.queryForList("""
            select a.status, a.opinion_id, a.hidden from resident_request_answers a
            join stores s on s.id = a.store_id where a.id = ? and s.owner_id = ? for update
            """, answer, owner));
        if (Boolean.TRUE.equals(old.get("hidden"))) throw conflict("숨김 처리된 답변은 수정할 수 없습니다.");
        AnswerController.AnswerStatus current = AnswerController.AnswerStatus.valueOf((String) old.get("status"));
        var target = input.status() == null ? current : input.status();
        if (target.ordinal() < current.ordinal()) throw conflict("답변 상태를 이전으로 되돌릴 수 없습니다.");
        String message = input.message() == null ? null : cleanMessage(input.message());
        db.update("""
            update resident_request_answers set message = coalesce(?, message), status = ?, updated_at = current_timestamp
            where id = ?
            """, message, target.name(), answer);
        if (current != target && target == AnswerController.AnswerStatus.ENDED) {
            notifyInterested(owner, (UUID) old.get("opinion_id"), answer, "ANSWER_ENDED");
        }
        return visible(answer);
    }

    List<Map<String, Object>> list(UUID wish, int size, int start) {
        first(db.queryForList("select id from building_opinions where id = ?", UUID.class, wish));
        return db.queryForList(PUBLIC_ANSWER + " where a.opinion_id = ? and not a.hidden order by a.created_at desc, a.id limit ? offset ?",
            wish, limit(size), offset(start));
    }

    List<Map<String, Object>> storeAnswers(UUID owner, UUID store, int size, int start) {
        stores.owned(owner, store);
        return db.queryForList(PUBLIC_ANSWER + " where a.store_id = ? order by a.created_at desc, a.id limit ? offset ?",
            store, limit(size), offset(start));
    }

    Map<String, Object> show(UUID account, UUID answer) {
        first(db.queryForList("""
            select id from resident_alerts where answer_id = ? and account_id = ? and kind = 'ANSWER_POSTED'
            """, UUID.class, answer, account));
        var card = visible(answer);
        card.put("firstVisitBenefit", db.queryForObject("select first_visit_benefit from stores where id = ?", String.class, card.get("storeId")));
        return card;
    }

    @Transactional
    Map<String, Object> visit(UUID account, UUID answer) {
        if ("PREPARING".equals(visible(answer).get("status"))) throw conflict("판매가 시작된 뒤 방문을 표시할 수 있습니다.");
        db.update("insert into answer_visits(answer_id, account_id) values (?, ?) on conflict do nothing", answer, account);
        return Map.of("visitedByMe", true, "visitCount", db.queryForObject(
            "select count(*) from answer_visits where answer_id = ?", Long.class, answer));
    }

    void report(UUID account, UUID answer, String reason) {
        visible(answer);
        db.update("insert into answer_reports(answer_id, account_id, reason) values (?, ?, ?) on conflict do nothing",
            answer, account, reason.strip());
    }

    List<Map<String, Object>> reports(int size, int start) {
        return db.queryForList("""
            select r.answer_id as "answerId", a.message, a.hidden, s.name as "storeName", r.reason, r.created_at as "createdAt"
            from answer_reports r join resident_request_answers a on a.id = r.answer_id
            join stores s on s.id = a.store_id order by r.created_at desc, r.answer_id, r.account_id limit ? offset ?
            """, limit(size), offset(start));
    }

    void visibility(UUID answer, boolean hidden) {
        if (db.update("update resident_request_answers set hidden = ? where id = ?", hidden, answer) == 0) throw missing();
    }

    List<Map<String, Object>> alerts(UUID account, int size, int start) {
        return db.queryForList("""
            select n.id, n.answer_id as "answerId", a.opinion_id as "wishId", n.kind, s.name as "storeName",
                   n.created_at as "createdAt", n.read_at as "readAt"
            from resident_alerts n join resident_request_answers a on a.id = n.answer_id join stores s on s.id = a.store_id
            where n.account_id = ? and not a.hidden order by n.created_at desc, n.id limit ? offset ?
            """, account, limit(size), offset(start));
    }

    void readAlert(UUID account, UUID id) {
        if (db.update("""
            update resident_alerts set read_at = coalesce(read_at, current_timestamp) where id = ? and account_id = ?
            """, id, account) == 0) throw missing();
    }

    boolean wantsAlerts(UUID account) {
        var enabled = db.queryForList("select enabled from resident_alert_preferences where account_id = ?", Boolean.class, account);
        return enabled.isEmpty() || Boolean.TRUE.equals(enabled.getFirst());
    }

    @Transactional
    void toggleAlerts(UUID account, boolean enabled) {
        first(db.queryForList("select id from accounts where id = ? for update", UUID.class, account));
        if (db.update("update resident_alert_preferences set enabled = ? where account_id = ?", enabled, account) == 0) {
            db.update("insert into resident_alert_preferences(account_id, enabled) values (?, ?)", account, enabled);
        }
    }

    Map<String, Object> visible(UUID answer) {
        return first(db.queryForList(PUBLIC_ANSWER + " where a.id = ? and not a.hidden", answer));
    }

    private Map<String, Object> availableWish(Stores.Store store, UUID wishId) {
        var wish = first(db.queryForList(WISH_DETAILS + " where o.id = ?", wishId));
        double distance = distance(store, wish);
        if (distance > MAX_METERS) throw invalid("가게에서 300m 이내의 주민 요청만 답변할 수 있습니다.");
        wish.put("distanceMeters", Math.round(distance));
        return wish;
    }

    private static double distance(Stores.Store store, Map<String, Object> wish) {
        return GeoDistance.meters(store.latitude(), store.longitude(),
            (BigDecimal) wish.get("latitude"), (BigDecimal) wish.get("longitude"));
    }

    private void notifyInterested(UUID owner, UUID wish, UUID answer, String kind) {
        var recipients = db.queryForList("""
            select r.account_id from (
                select author_id as account_id from building_opinions where id = ?
                union select account_id from building_opinion_likes where opinion_id = ?
            ) r left join resident_alert_preferences p on p.account_id = r.account_id
            where coalesce(p.enabled, true) and r.account_id <> ?
            """, UUID.class, wish, wish, owner);
        if (recipients.isEmpty()) return;
        db.batchUpdate("""
            insert into resident_alerts(id, account_id, answer_id, kind) values (?, ?, ?, ?) on conflict do nothing
            """, recipients.stream().map(account -> new Object[]{UUID.randomUUID(), account, answer, kind}).toList());
    }

    private static String cleanMessage(String message) {
        if (message.isBlank() || DIRECT_CONTACT.matcher(message).find()) throw invalid("연락처나 링크 없이 답변을 입력하세요.");
        return message.strip();
    }
}

@RestController
@Tag(name = "주민 요청 답변·알림", description = "가게 주변 요청, 답변, 주민 알림과 이용 반응을 관리합니다.")
@RequestMapping("/api")
class AnswerController {
    private final ResidentAnswers answers;
    private final Stores stores;

    AnswerController(ResidentAnswers answers, Stores stores) {
        this.answers = answers;
        this.stores = stores;
    }

    @GetMapping("/stores/{id}/nearby-wishes")
    @Operation(summary = "내 가게 근처 주민 요청 조회", description = "150m 안의 같은 업종 요청과 공감이 많은 요청을 먼저 보여 줍니다. "
        + "150m 안에 요청이 없으면 300m 이내에서 가까운 세 건까지 반환합니다.")
    List<Map<String, Object>> nearby(@AuthenticationPrincipal Actor actor, @PathVariable UUID id,
        @RequestParam(defaultValue = "30") int limit) {
        return answers.nearby(stores.owned(actor.require(Role.OWNER), id), limit);
    }

    @PostMapping("/stores/{id}/themes")
    @ResponseStatus(ACCEPTED)
    @Operation(summary = "근처 주민 요청 공통점 분석 요청", description = "최근접 요청을 묶고 제안하는 AI 작업을 등록합니다.")
    Map<String, Object> themes(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return answers.themes(actor.require(Role.OWNER), id);
    }

    @PostMapping("/stores/{id}/answer-draft")
    @ResponseStatus(ACCEPTED)
    @Operation(summary = "주민 요청 답변 초안 생성 요청", description = "가게 설정과 주민 요청을 AI 워커에 전달하고, 결과 조회에 사용할 작업 ID를 반환합니다.")
    Map<String, Object> draft(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody Draft input) {
        return answers.draft(actor.require(Role.OWNER), id, input.wishId());
    }

    @GetMapping("/wishes/{id}/answers")
    @Operation(summary = "주민 요청에 달린 가게 답변 조회", description = "숨김 처리되지 않은 가게 답변과 상태를 공개합니다.")
    List<Map<String, Object>> list(@PathVariable UUID id, @RequestParam(defaultValue = "30") int limit,
        @RequestParam(defaultValue = "0") int offset) {
        return answers.list(id, limit, offset);
    }

    @GetMapping("/stores/{id}/answers")
    @Operation(summary = "내 가게가 올린 주민 요청 답변 조회", description = "현재 가게의 답변과 메뉴, 상태를 조회합니다.")
    List<Map<String, Object>> storeAnswers(@AuthenticationPrincipal Actor actor, @PathVariable UUID id,
        @RequestParam(defaultValue = "30") int limit, @RequestParam(defaultValue = "0") int offset) {
        return answers.storeAnswers(actor.require(Role.OWNER), id, limit, offset);
    }

    @PostMapping("/wishes/{id}/answers")
    @ResponseStatus(CREATED)
    @Operation(summary = "주민 요청에 가게 답변 올리기", description = "가게당 요청 하나에 답변을 한 번 올리고 요청 작성자와 공감한 계정에 사이트 알림을 생성합니다.")
    Map<String, Object> publish(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody NewAnswer input) {
        return answers.publish(actor.require(Role.OWNER), id, input);
    }

    @PatchMapping("/answers/{id}")
    @Operation(summary = "가게 답변 또는 판매 상태 변경", description = "답변 작성자가 내용을 수정하고 PREPARING, SELLING, ENDED로 상태를 진행합니다.")
    Map<String, Object> edit(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody Change input) {
        if (input.message() == null && input.status() == null) throw invalid("수정할 내용을 하나 이상 지정하세요.");
        return answers.edit(actor.require(Role.OWNER), id, input);
    }

    @GetMapping("/answers/{id}/show")
    @Operation(summary = "가게에서 보여 줄 알림 카드 조회", description = "답변 게시 알림을 받은 본인에게 가게, 메뉴와 첫 방문 혜택을 반환합니다.")
    Map<String, Object> show(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return answers.show(actor.id(), id);
    }

    @PostMapping("/answers/{id}/went")
    @Operation(summary = "가게를 방문했다는 표시 등록", description = "로그인한 이용자의 방문 표시를 답변당 1회 집계합니다.")
    Map<String, Object> visit(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return answers.visit(actor.id(), id);
    }

    @PostMapping("/answers/{id}/report")
    @ResponseStatus(NO_CONTENT)
    @Operation(summary = "공개 답변 신고", description = "로그인한 이용자가 신고 사유를 남깁니다. 같은 답변에 계정당 한 번만 기록합니다.")
    void report(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody Report input) {
        answers.report(actor.id(), id, input.reason());
    }

    @GetMapping("/me/alerts")
    @Operation(summary = "내 주민 요청 알림 조회", description = "작성하거나 공감한 요청에 붙은 가게 답변 게시·종료 알림을 조회합니다.")
    List<Map<String, Object>> alerts(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "30") int limit,
        @RequestParam(defaultValue = "0") int offset) {
        return answers.alerts(actor.id(), limit, offset);
    }

    @PutMapping("/me/alerts/{id}/read")
    @ResponseStatus(NO_CONTENT)
    @Operation(summary = "내 알림 읽음 처리", description = "해당 알림의 첫 확인 시각을 기록합니다.")
    void read(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        answers.readAlert(actor.id(), id);
    }

    @GetMapping("/me/alerts/preferences")
    @Operation(summary = "주민 요청 알림 설정 조회", description = "현재 계정의 새 알림 수신 여부를 반환합니다.")
    Map<String, Boolean> preferences(@AuthenticationPrincipal Actor actor) {
        return Map.of("enabled", answers.wantsAlerts(actor.id()));
    }

    @PutMapping("/me/alerts/preferences")
    @ResponseStatus(NO_CONTENT)
    @Operation(summary = "주민 요청 알림 설정 저장", description = "가게가 답변을 게시하거나 종료할 때 새 알림을 받을지 선택합니다.")
    void preferences(@AuthenticationPrincipal Actor actor, @Valid @RequestBody AlertPreference input) {
        answers.toggleAlerts(actor.id(), input.enabled());
    }

    @GetMapping("/operations/answer-reports")
    @Operation(summary = "운영단 답변 신고 목록 조회", description = "운영단 전용으로 주민이 신고한 답변과 사유, 숨김 상태를 조회합니다.")
    List<Map<String, Object>> reports(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "30") int limit,
        @RequestParam(defaultValue = "0") int offset) {
        actor.require(Role.OPERATOR);
        return answers.reports(limit, offset);
    }

    @PutMapping("/operations/answers/{id}/visibility")
    @ResponseStatus(NO_CONTENT)
    @Operation(summary = "운영단 답변 공개 상태 변경", description = "운영단이 신고 검토 후 답변을 숨기거나 다시 표시합니다.")
    void visibility(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody Visibility input) {
        actor.require(Role.OPERATOR);
        answers.visibility(id, input.hidden());
    }

    enum AnswerStatus { PREPARING, SELLING, ENDED }
    record Draft(@NotNull UUID wishId) {}
    record NewAnswer(@NotNull UUID storeId, UUID menuId, @Size(max = 120) String menuName,
        @NotBlank @Size(max = 1000) String message) {}
    record Change(@Size(max = 1000) String message, AnswerStatus status) {}
    record Report(@NotBlank @Size(max = 500) String reason) {}
    record AlertPreference(@NotNull Boolean enabled) {}
    record Visibility(@NotNull Boolean hidden) {}
}
