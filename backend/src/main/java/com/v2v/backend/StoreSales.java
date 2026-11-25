package com.v2v.backend;

import static com.v2v.backend.Api.*;
import static org.springframework.http.HttpStatus.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
class StoreSales {
    static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final String INGREDIENTS = """
        select id, name, unit, on_hand as "onHand", pack_quantity as "packQuantity",
               lead_days as "leadDays", updated_at as "updatedAt" from store_ingredients where store_id = ?
        """;
    private static final String ORDERS = """
        select id, ingredient_id as "ingredientId", quantity, arrives_on as "arrivesOn", status,
               created_at as "createdAt" from store_supply_orders where store_id = ?
        """;
    private final JdbcTemplate db;
    private final Stores stores;

    StoreSales(JdbcTemplate db, Stores stores) {
        this.db = db;
        this.stores = stores;
    }

    @Transactional
    Map<String, Object> sale(UUID owner, UUID store, UUID menu, LocalDate date, SalesController.DailySale input) {
        stores.owned(owner, store);
        stores.requireMenu(store, menu);
        return recordSale(menu, date, input.soldQuantity(), input.salesKrw(), input.soldOut());
    }

    @Transactional
    Map<String, Object> answerDay(UUID owner, UUID answer, SalesController.AnswerDay input) {
        var row = first(db.queryForList("""
            select a.menu_id from resident_request_answers a join stores s on s.id = a.store_id
            where a.id = ? and s.owner_id = ?
            """, answer, owner));
        var result = recordSale((UUID) row.get("menu_id"), input.date(), input.soldQuantity(), input.salesKrw(), input.soldOut());
        if (input.shownVisits() != null) {
            db.update("""
                insert into answer_visit_days(answer_id, visited_on, shown_visits) values (?, ?, ?) on conflict do nothing
                """, answer, input.date(), input.shownVisits());
            db.update("update answer_visit_days set shown_visits = ? where answer_id = ? and visited_on = ?",
                input.shownVisits(), answer, input.date());
        }
        result.put("answerId", answer);
        result.put("shownVisits", db.queryForList("""
            select shown_visits from answer_visit_days where answer_id = ? and visited_on = ?
            """, Integer.class, answer, input.date()).stream().findFirst().orElse(0));
        return result;
    }

    private Map<String, Object> recordSale(UUID menu, LocalDate date, int quantity, Long sales, boolean soldOut) {
        if (date.isAfter(today())) throw invalid("판매 기록 날짜는 오늘까지 입력할 수 있습니다.");
        db.update("""
            insert into store_menu_days(menu_id, sold_on, sold_quantity, sales_krw, sold_out)
            values (?, ?, ?, ?, ?) on conflict do nothing
            """, menu, date, quantity, sales, soldOut);
        db.update("update store_menu_days set sold_quantity = ?, sales_krw = ?, sold_out = ? where menu_id = ? and sold_on = ?",
            quantity, sales, soldOut, menu, date);
        return first(db.queryForList("""
            select menu_id as "menuId", sold_on as "date", sold_quantity as "soldQuantity", sales_krw as "salesKrw",
                   sold_out as "soldOut" from store_menu_days where menu_id = ? and sold_on = ?
            """, menu, date));
    }

    List<Map<String, Object>> sales(UUID owner, UUID store, LocalDate start, LocalDate end, int size, int skip) {
        stores.owned(owner, store);
        checkRange(start, end);
        return db.queryForList("""
            select d.sold_on as "date", m.id as "menuId", m.name as "menuName", d.sold_quantity as "soldQuantity",
                   d.sales_krw as "salesKrw", d.sold_out as "soldOut"
            from store_menu_days d join store_menus m on m.id = d.menu_id where m.store_id = ? and d.sold_on between ? and ?
            order by d.sold_on desc, m.id limit ? offset ?
            """, store, start, end, limit(size), offset(skip));
    }

    List<Map<String, Object>> ingredients(UUID owner, UUID store) {
        stores.owned(owner, store);
        return db.queryForList(INGREDIENTS + " order by name, id", store);
    }

    Map<String, Object> addIngredient(UUID owner, UUID store, SalesController.Ingredient input) {
        stores.owned(owner, store);
        UUID id = UUID.randomUUID();
        db.update("""
            insert into store_ingredients(id, store_id, name, unit, on_hand, pack_quantity, lead_days)
            values (?, ?, ?, ?, ?, ?, ?)
            """, id, store, input.name().strip(), input.unit().strip(), input.onHand(), input.packQuantity(), input.leadDays());
        return ingredient(store, id);
    }

    Map<String, Object> stock(UUID owner, UUID store, UUID ingredient, BigDecimal onHand) {
        stores.owned(owner, store);
        if (db.update("update store_ingredients set on_hand = ?, updated_at = current_timestamp where id = ? and store_id = ?",
            onHand, ingredient, store) == 0) throw missing();
        return ingredient(store, ingredient);
    }

    private Map<String, Object> ingredient(UUID store, UUID id) {
        return first(db.queryForList(INGREDIENTS + " and id = ?", store, id));
    }

    @Transactional
    void recipe(UUID owner, UUID store, UUID menu, UUID ingredient, BigDecimal quantity) {
        stores.owned(owner, store);
        stores.requireMenu(store, menu);
        ingredient(store, ingredient);
        db.update("""
            insert into store_menu_ingredients(menu_id, ingredient_id, store_id, quantity_per_sale)
            values (?, ?, ?, ?) on conflict do nothing
            """, menu, ingredient, store, quantity);
        db.update("update store_menu_ingredients set quantity_per_sale = ? where menu_id = ? and ingredient_id = ?",
            quantity, menu, ingredient);
    }

    List<Map<String, Object>> recipes(UUID owner, UUID store, UUID menu) {
        stores.owned(owner, store);
        stores.requireMenu(store, menu);
        return db.queryForList("""
            select i.id as "ingredientId", i.name, i.unit, r.quantity_per_sale as "quantityPerSale"
            from store_menu_ingredients r join store_ingredients i on i.id = r.ingredient_id
            where r.menu_id = ? and r.store_id = ? order by i.name, i.id
            """, menu, store);
    }

    List<Map<String, Object>> orders(UUID owner, UUID store, int size, int start) {
        stores.owned(owner, store);
        return db.queryForList(ORDERS + " order by created_at desc, id limit ? offset ?", store, limit(size), offset(start));
    }

    Map<String, Object> order(UUID owner, UUID store, SalesController.NewOrder input) {
        stores.owned(owner, store);
        ingredient(store, input.ingredientId());
        if (input.arrivesOn().isBefore(today())) throw invalid("도착 예정일은 오늘 이후로 지정하세요.");
        UUID id = UUID.randomUUID();
        db.update("""
            insert into store_supply_orders(id, ingredient_id, store_id, quantity, arrives_on, status)
            values (?, ?, ?, ?, ?, 'ORDERED')
            """, id, input.ingredientId(), store, input.quantity(), input.arrivesOn());
        return first(db.queryForList(ORDERS + " and id = ?", store, id));
    }

    @Transactional
    Map<String, Object> finishOrder(UUID owner, UUID store, UUID id, SalesController.OrderStatus status) {
        stores.owned(owner, store);
        var current = first(db.queryForList(ORDERS + " and id = ? for update", store, id));
        if (status.name().equals(current.get("status"))) return current;
        if (!"ORDERED".equals(current.get("status"))) throw conflict("도착 또는 취소로 확정된 주문입니다.");
        db.update("update store_supply_orders set status = ? where id = ?", status.name(), id);
        if (status == SalesController.OrderStatus.RECEIVED) {
            db.update("update store_ingredients set on_hand = on_hand + ?, updated_at = current_timestamp where id = ?",
                current.get("quantity"), current.get("ingredientId"));
        }
        return first(db.queryForList(ORDERS + " and id = ?", store, id));
    }

    @Transactional(readOnly = true)
    List<Map<String, Object>> forecast(UUID owner, UUID store) {
        var materials = ingredients(owner, store);
        LocalDate today = today();
        var recipeSales = db.queryForList("""
            select r.ingredient_id as "ingredientId", m.id as "menuId", m.name as "menuName",
                   r.quantity_per_sale as "quantityPerSale", count(d.sold_on) as "recordedDays",
                   coalesce(sum(d.sold_quantity), 0) as "soldQuantity",
                   sum(case when d.sold_out then 1 else 0 end) as "soldOutDays"
            from store_menu_ingredients r join store_menus m on m.id = r.menu_id and m.active
            left join store_menu_days d on d.menu_id = m.id and d.sold_on between ? and ?
            where r.store_id = ? group by r.ingredient_id, m.id, m.name, r.quantity_per_sale order by m.name, m.id
            """, today.minusDays(7), today.minusDays(1), store);
        var output = new ArrayList<Map<String, Object>>();
        for (var material : materials) {
            UUID id = (UUID) material.get("id");
            var sources = recipeSales.stream().filter(row -> id.equals(row.get("ingredientId"))).toList();
            material.put("sources", sources);
            material.put("referenceFrom", today.minusDays(7));
            material.put("referenceTo", today.minusDays(1));
            if (sources.isEmpty() || sources.stream().anyMatch(row -> ((Number) row.get("recordedDays")).longValue() < 3)) {
                material.put("status", sources.isEmpty() ? "NO_RECIPE" : "INSUFFICIENT_DATA");
                output.add(material);
                continue;
            }
            BigDecimal perDay = sources.stream().map(row -> decimal(row, "quantityPerSale")
                    .multiply(decimal(row, "soldQuantity")).divide(decimal(row, "recordedDays"), 8, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add).multiply(new BigDecimal("1.10"));
            int lead = ((Number) material.get("leadDays")).intValue();
            BigDecimal onHand = decimal(material, "onHand");
            BigDecimal pack = decimal(material, "packQuantity");
            BigDecimal tomorrow = perDay.setScale(3, RoundingMode.CEILING);
            LocalDate orderWindow = today.plusDays(lead + 1L);
            BigDecimal orderNeed = perDay.multiply(BigDecimal.valueOf(lead + 1L));
            BigDecimal incoming = incoming(store, id, today, orderWindow);
            BigDecimal missing = orderNeed.subtract(onHand).subtract(incoming).max(BigDecimal.ZERO);
            material.put("status", "READY");
            material.put("tomorrowRequired", tomorrow);
            material.put("tomorrowShortage", tomorrow.subtract(onHand).subtract(incoming(store, id, today, today.plusDays(1)))
                .max(BigDecimal.ZERO).setScale(3, RoundingMode.CEILING));
            material.put("orderThrough", orderWindow);
            material.put("incomingQuantity", incoming);
            material.put("recommendedOrderQuantity", missing.divide(pack, 0, RoundingMode.CEILING).multiply(pack));
            material.put("soldOutInReferencePeriod", sources.stream().anyMatch(row -> ((Number) row.get("soldOutDays")).intValue() > 0));
            output.add(material);
        }
        return output;
    }

    private BigDecimal incoming(UUID store, UUID ingredient, LocalDate start, LocalDate end) {
        return db.queryForObject("""
            select coalesce(sum(quantity), 0) from store_supply_orders
            where store_id = ? and ingredient_id = ? and status = 'ORDERED' and arrives_on between ? and ?
            """, BigDecimal.class, store, ingredient, start, end);
    }

    private static BigDecimal decimal(Map<String, Object> row, String field) {
        return new BigDecimal(row.get(field).toString());
    }

    static LocalDate today() {
        return LocalDate.now(KOREA);
    }

    static void checkRange(LocalDate start, LocalDate end) {
        if (end.isBefore(start) || end.isAfter(start.plusYears(1))) throw invalid("조회 기간은 최대 1년입니다.");
    }
}

@RestController
@Tag(name = "판매·재료·발주", description = "메뉴 판매 기록, 남은 재료, 준비량과 발주를 관리합니다.")
@RequestMapping("/api")
class SalesController {
    private final StoreSales sales;

    SalesController(StoreSales sales) {
        this.sales = sales;
    }

    @PutMapping("/stores/{id}/menus/{menuId}/days/{date}")
    @Operation(summary = "메뉴의 하루 판매 기록 저장", description = "날짜별 판매 수량, 매출액과 품절 여부를 등록하거나 갱신합니다.")
    Map<String, Object> record(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @PathVariable UUID menuId,
        @PathVariable LocalDate date, @Valid @RequestBody DailySale input) {
        return sales.sale(actor.require(Role.OWNER), id, menuId, date, input);
    }

    @PostMapping("/answers/{id}/days")
    @Operation(summary = "답변 메뉴의 하루 판매와 알림 방문 저장", description = "답변에 연결된 메뉴의 판매를 기록합니다. "
        + "shownVisits에 알림을 보여 준 손님 수를 넣으면 답변과 날짜별로 저장합니다.")
    Map<String, Object> answerDay(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody AnswerDay input) {
        return sales.answerDay(actor.require(Role.OWNER), id, input);
    }

    @GetMapping("/stores/{id}/sales")
    @Operation(summary = "내 가게의 판매 기록 조회", description = "from, to에 날짜를 지정해 메뉴별 판매, 매출 및 품절 기록을 조회합니다.")
    List<Map<String, Object>> list(@AuthenticationPrincipal Actor actor, @PathVariable UUID id,
        @RequestParam LocalDate from, @RequestParam LocalDate to, @RequestParam(defaultValue = "30") int limit,
        @RequestParam(defaultValue = "0") int offset) {
        return sales.sales(actor.require(Role.OWNER), id, from, to, limit, offset);
    }

    @GetMapping("/stores/{id}/ingredients")
    @Operation(summary = "내 가게에 남은 재료 조회", description = "재료별 현재 보유량, 단위, 발주 묶음 크기와 도착 소요 일수를 반환합니다.")
    List<Map<String, Object>> ingredients(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return sales.ingredients(actor.require(Role.OWNER), id);
    }

    @PostMapping("/stores/{id}/ingredients")
    @ResponseStatus(CREATED)
    @Operation(summary = "가게 재료 등록", description = "재료의 현재 보유량, 단위, 발주 묶음 크기와 도착 소요 일수를 저장합니다.")
    Map<String, Object> ingredient(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody Ingredient input) {
        return sales.addIngredient(actor.require(Role.OWNER), id, input);
    }

    @PutMapping("/stores/{id}/ingredients/{ingredientId}/stock")
    @Operation(summary = "현재 남은 재료량 수정", description = "직접 센 재료의 현재 보유량을 해당 재료의 단위에 맞춰 저장합니다.")
    Map<String, Object> stock(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @PathVariable UUID ingredientId,
        @Valid @RequestBody Stock input) {
        return sales.stock(actor.require(Role.OWNER), id, ingredientId, input.onHand());
    }

    @GetMapping("/stores/{id}/menus/{menuId}/ingredients")
    @Operation(summary = "메뉴에 연결된 재료의 1인분 기준량 조회", description = "메뉴 한 건 판매에 쓰이는 재료와 각 재료 단위의 사용량을 조회합니다.")
    List<Map<String, Object>> recipe(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @PathVariable UUID menuId) {
        return sales.recipes(actor.require(Role.OWNER), id, menuId);
    }

    @PutMapping("/stores/{id}/menus/{menuId}/ingredients/{ingredientId}")
    @ResponseStatus(NO_CONTENT)
    @Operation(summary = "메뉴 한 건에 들어가는 재료량 저장", description = "가게의 메뉴와 재료를 연결하고 판매 한 건당 사용량을 지정합니다.")
    void recipe(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @PathVariable UUID menuId,
        @PathVariable UUID ingredientId, @Valid @RequestBody Recipe input) {
        sales.recipe(actor.require(Role.OWNER), id, menuId, ingredientId, input.quantityPerSale());
    }

    @GetMapping("/stores/{id}/orders")
    @Operation(summary = "내 가게 발주 기록 조회", description = "발주한 재료의 양, 도착 예정일과 처리 상태를 최신순으로 반환합니다.")
    List<Map<String, Object>> orders(@AuthenticationPrincipal Actor actor, @PathVariable UUID id,
        @RequestParam(defaultValue = "30") int limit, @RequestParam(defaultValue = "0") int offset) {
        return sales.orders(actor.require(Role.OWNER), id, limit, offset);
    }

    @PostMapping("/stores/{id}/orders")
    @ResponseStatus(CREATED)
    @Operation(summary = "재료 발주 기록 추가", description = "확정한 발주 재료, 수량과 도착 예정일을 가게 기록에 저장합니다.")
    Map<String, Object> order(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody NewOrder input) {
        return sales.order(actor.require(Role.OWNER), id, input);
    }

    @PutMapping("/stores/{id}/orders/{orderId}/status")
    @Operation(summary = "재료 발주 기록을 도착 또는 취소 처리", description = "RECEIVED로 처음 변경할 때 보유 재료에 한 번 반영합니다. "
        + "CANCELLED는 보유량에 반영하지 않습니다.")
    Map<String, Object> orderStatus(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @PathVariable UUID orderId,
        @Valid @RequestBody OrderUpdate input) {
        return sales.finishOrder(actor.require(Role.OWNER), id, orderId, input.status());
    }

    @GetMapping("/stores/{id}/stock-recommendations")
    @Operation(summary = "재료 준비량과 발주량 조회", description = "최근 7일간 기록을 바탕으로 다음 영업 준비량과 발주 권장량을 계산합니다. "
        + "판매 기록이 메뉴별 3일 이상 필요하며, 지난 품절 기록은 함께 표시합니다.")
    List<Map<String, Object>> forecast(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return sales.forecast(actor.require(Role.OWNER), id);
    }

    record DailySale(@NotNull @PositiveOrZero Integer soldQuantity, @PositiveOrZero Long salesKrw,
        @NotNull Boolean soldOut) {}
    record AnswerDay(@NotNull LocalDate date, @NotNull @PositiveOrZero Integer soldQuantity,
        @PositiveOrZero Long salesKrw, @NotNull Boolean soldOut, @PositiveOrZero Integer shownVisits) {}
    record Ingredient(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 20) String unit,
        @NotNull @PositiveOrZero @Digits(integer = 11, fraction = 3) BigDecimal onHand,
        @NotNull @Positive @Digits(integer = 11, fraction = 3) BigDecimal packQuantity,
        @NotNull @Min(0) @Max(30) Integer leadDays) {}
    record Stock(@NotNull @PositiveOrZero @Digits(integer = 11, fraction = 3) BigDecimal onHand) {}
    record Recipe(@NotNull @Positive @Digits(integer = 11, fraction = 3) BigDecimal quantityPerSale) {}
    record NewOrder(@NotNull UUID ingredientId, @NotNull @Positive @Digits(integer = 11, fraction = 3) BigDecimal quantity,
        @NotNull LocalDate arrivesOn) {}
    record OrderUpdate(@NotNull OrderStatus status) {}
    enum OrderStatus { RECEIVED, CANCELLED }
}
