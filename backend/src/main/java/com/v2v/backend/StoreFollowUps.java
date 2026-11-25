package com.v2v.backend;

import static com.v2v.backend.Api.*;
import static org.springframework.http.HttpStatus.NO_CONTENT;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.YearMonth;
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
class StoreFollowUps {
    private final JdbcTemplate db;
    private final Stores stores;

    StoreFollowUps(JdbcTemplate db, Stores stores) {
        this.db = db;
        this.stores = stores;
    }

    Map<String, Object> preferences(UUID owner, UUID store) {
        stores.owned(owner, store);
        return db.queryForList("""
            select share_status as "shareStatus", share_sales as "shareSales", share_visits as "shareVisits"
            from store_sharing where store_id = ?
            """, store).stream().findFirst().orElseGet(() -> Map.of("shareStatus", false, "shareSales", false, "shareVisits", false));
    }

    @Transactional
    void sharing(UUID owner, UUID store, FollowUpController.Sharing input) {
        stores.owned(owner, store);
        db.update("""
            insert into store_sharing(store_id, share_status, share_sales, share_visits)
            values (?, ?, ?, ?) on conflict do nothing
            """, store, input.shareStatus(), input.shareSales(), input.shareVisits());
        db.update("update store_sharing set share_status = ?, share_sales = ?, share_visits = ? where store_id = ?",
            input.shareStatus(), input.shareSales(), input.shareVisits(), store);
    }

    @Transactional
    Map<String, Object> save(UUID owner, UUID store, YearMonth month, FollowUpController.Confirm input) {
        stores.owned(owner, store);
        LocalDate start = startOf(month);
        String band = input.salesBand() == null ? null : input.salesBand().name();
        db.update("""
            insert into store_follow_ups(store_id, month_start, business_status, sales_band)
            values (?, ?, ?, ?) on conflict do nothing
            """, store, start, input.businessStatus().name(), band);
        db.update("""
            update store_follow_ups set business_status = ?, sales_band = ?, updated_at = current_timestamp
            where store_id = ? and month_start = ?
            """, input.businessStatus().name(), band, store, start);
        return summary(owner, store, month);
    }

    @Transactional(readOnly = true)
    Map<String, Object> summary(UUID owner, UUID store, YearMonth month) {
        stores.owned(owner, store);
        LocalDate start = startOf(month);
        LocalDate end = start.plusMonths(1);
        var row = new LinkedHashMap<String, Object>();
        row.put("month", month.toString());
        row.put("storeId", store);
        row.put("confirmation", db.queryForList("""
            select business_status as "businessStatus", sales_band as "salesBand", updated_at as "updatedAt"
            from store_follow_ups where store_id = ? and month_start = ?
            """, store, start).stream().findFirst().orElse(null));
        row.put("sales", db.queryForMap("""
            select count(distinct d.sold_on) as "recordedDays", coalesce(sum(d.sold_quantity), 0) as "soldQuantity",
                   sum(d.sales_krw) as "salesKrw"
            from store_menu_days d join store_menus m on m.id = d.menu_id
            where m.store_id = ? and d.sold_on >= ? and d.sold_on < ?
            """, store, start, end));
        row.put("visits", db.queryForMap("""
            select
                (select coalesce(sum(d.shown_visits), 0) from answer_visit_days d
                 join resident_request_answers a on a.id = d.answer_id
                 where a.store_id = ? and d.visited_on >= ? and d.visited_on < ?) as "shownVisits",
                (select count(distinct v.account_id) from answer_visits v join resident_request_answers a on a.id = v.answer_id
                 where a.store_id = ? and v.visited_at >= ? and v.visited_at < ?) as "uniqueSelfReportedVisitors"
            """, store, start, end, store, start.atStartOfDay(StoreSales.KOREA).toOffsetDateTime(),
            end.atStartOfDay(StoreSales.KOREA).toOffsetDateTime()));
        return row;
    }

    List<Map<String, Object>> report(YearMonth month, int size, int startAt) {
        LocalDate start = startOf(month);
        LocalDate end = start.plusMonths(1);
        return db.queryForList("""
            select s.id as "storeId", s.name as "storeName",
                case when c.share_status then f.business_status end as "businessStatus",
                case when c.share_sales then f.sales_band end as "salesBand",
                case when c.share_sales then
                    (select count(distinct d.sold_on) from store_menu_days d join store_menus m on m.id = d.menu_id
                     where m.store_id = s.id and d.sold_on >= ? and d.sold_on < ?) end as "recordedDays",
                case when c.share_sales then
                    (select coalesce(sum(d.sold_quantity), 0) from store_menu_days d join store_menus m on m.id = d.menu_id
                     where m.store_id = s.id and d.sold_on >= ? and d.sold_on < ?) end as "soldQuantity",
                case when c.share_visits then
                    (select coalesce(sum(d.shown_visits), 0) from answer_visit_days d
                     join resident_request_answers a on a.id = d.answer_id
                     where a.store_id = s.id and d.visited_on >= ? and d.visited_on < ?) end as "shownVisits",
                case when c.share_visits then
                    (select count(distinct v.account_id) from answer_visits v join resident_request_answers a on a.id = v.answer_id
                     where a.store_id = s.id and v.visited_at >= ? and v.visited_at < ?) end as "uniqueSelfReportedVisitors"
            from stores s join store_sharing c on c.store_id = s.id
            left join store_follow_ups f on f.store_id = s.id and f.month_start = ?
            where c.share_status or c.share_sales or c.share_visits
            order by s.name, s.id limit ? offset ?
            """, start, end, start, end, start, end, start.atStartOfDay(StoreSales.KOREA).toOffsetDateTime(),
            end.atStartOfDay(StoreSales.KOREA).toOffsetDateTime(), start, limit(size), offset(startAt));
    }

    private static LocalDate startOf(YearMonth month) {
        if (month.getYear() < 2000 || month.isAfter(YearMonth.from(StoreSales.today()))) {
            throw invalid("이번 달 또는 이전의 연월을 지정하세요.");
        }
        return month.atDay(1);
    }
}

@RestController
@Tag(name = "개업 후 운영 확인", description = "가게의 월간 기록과 운영단에 공유할 정보를 관리합니다.")
@RequestMapping("/api")
class FollowUpController {
    private final StoreFollowUps followUps;

    FollowUpController(StoreFollowUps followUps) {
        this.followUps = followUps;
    }

    @GetMapping("/stores/{id}/sharing")
    @Operation(summary = "가게의 운영정보 공유 동의 조회", description = "영업 상태, 판매 집계, 방문 집계에 대해 사장님이 정한 동의 상태를 반환합니다.")
    Map<String, Object> sharing(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return followUps.preferences(actor.require(Role.OWNER), id);
    }

    @PutMapping("/stores/{id}/sharing")
    @ResponseStatus(NO_CONTENT)
    @Operation(summary = "가게의 운영정보 공유 동의 저장", description = "사장님이 선택한 항목을 운영단 월간 조회에 즉시 반영합니다.")
    void sharing(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody Sharing input) {
        followUps.sharing(actor.require(Role.OWNER), id, input);
    }

    @GetMapping("/stores/{id}/follow-ups/{month}")
    @Operation(summary = "내 가게 월간 운영 요약 조회", description = "월별로 사장님이 확인한 영업 상태와 판매·방문 집계를 반환합니다. 월 형식은 YYYY-MM입니다.")
    Map<String, Object> monthly(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @PathVariable YearMonth month) {
        return followUps.summary(actor.require(Role.OWNER), id, month);
    }

    @PutMapping("/stores/{id}/follow-ups/{month}")
    @Operation(summary = "이번 달 영업 상태와 매출 구간 확인", description = "사장님이 확인한 영업 상태와 매출 구간을 YYYY-MM 월 단위로 저장합니다.")
    Map<String, Object> monthly(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @PathVariable YearMonth month,
        @Valid @RequestBody Confirm input) {
        return followUps.save(actor.require(Role.OWNER), id, month, input);
    }

    @GetMapping("/track")
    @Operation(summary = "운영단 월별 가게 운영 조회", description = "운영단에 공유를 동의한 가게의 지정 월 YYYY-MM, 영업 상태와 판매·방문 집계를 조회합니다. "
        + "정확한 매출액 대신 공유에 동의한 매출 구간을 반환합니다.")
    List<Map<String, Object>> track(@AuthenticationPrincipal Actor actor, @RequestParam YearMonth term,
        @RequestParam(defaultValue = "30") int limit, @RequestParam(defaultValue = "0") int offset) {
        actor.require(Role.OPERATOR);
        return followUps.report(term, limit, offset);
    }

    record Sharing(@NotNull Boolean shareStatus, @NotNull Boolean shareSales, @NotNull Boolean shareVisits) {}
    record Confirm(@NotNull BusinessStatus businessStatus, SalesBand salesBand) {}
    enum BusinessStatus { OPERATING, PAUSED, CLOSED }
    enum SalesBand { UNDER_1M, FROM_1M_TO_3M, FROM_3M_TO_5M, FROM_5M_TO_10M, FROM_10M }
}
