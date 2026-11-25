package com.v2v.backend;

import static com.v2v.backend.Api.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
class Stores {
    private static final String PROFILE = """
        select id, name, category, road_address as "roadAddress", longitude, latitude,
               first_visit_benefit as "firstVisitBenefit", offer_details as "offerDetails", created_at as "createdAt"
        from stores where owner_id = ?
        """;
    private final JdbcTemplate db;

    Stores(JdbcTemplate db) {
        this.db = db;
    }

    Map<String, Object> profile(UUID owner) {
        var store = first(db.queryForList(PROFILE, owner));
        store.put("menus", db.queryForList("""
            select id, name, price_krw as "priceKrw" from store_menus
            where store_id = ? and active order by name, id
            """, store.get("id")));
        return store;
    }

    @Transactional
    Map<String, Object> save(UUID owner, UUID requestedId, StoreController.Settings input) {
        first(db.queryForList("select id from accounts where id = ? for update", UUID.class, owner));
        var existing = db.queryForList("select id from stores where owner_id = ? for update", UUID.class, owner);
        UUID id = existing.isEmpty() ? UUID.randomUUID() : existing.getFirst();
        if (requestedId != null && !requestedId.equals(id)) throw missing();
        if (existing.isEmpty()) {
            db.update("""
                insert into stores(id, owner_id, name, category, road_address, longitude, latitude, first_visit_benefit, offer_details)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, owner, input.name().strip(), input.category().strip(), optional(input.roadAddress()),
                input.longitude(), input.latitude(), optional(input.firstVisitBenefit()), optional(input.offerDetails()));
        } else {
            db.update("""
                update stores set name = ?, category = ?, road_address = ?, longitude = ?, latitude = ?,
                    first_visit_benefit = ?, offer_details = ? where id = ?
                """, input.name().strip(), input.category().strip(), optional(input.roadAddress()),
                input.longitude(), input.latitude(), optional(input.firstVisitBenefit()), optional(input.offerDetails()), id);
        }
        if (input.menus() != null) updateMenus(id, input.menus());
        return profile(owner);
    }

    private void updateMenus(UUID store, List<StoreController.Menu> menus) {
        var names = new HashSet<String>();
        var ids = new HashSet<UUID>();
        db.update("update store_menus set active = false where store_id = ?", store);
        for (var input : menus) {
            String name = input.name().strip();
            if (!names.add(name)) throw invalid("메뉴 이름을 중복 입력할 수 없습니다.");
            UUID menu = input.id() == null ? ensureMenu(store, name) : input.id();
            if (!ids.add(menu)) throw invalid("메뉴를 중복 입력할 수 없습니다.");
            int rank = db.update("""
                update store_menus set name = ?, price_krw = ?, active = true where store_id = ? and id = ?
                """, name, input.priceKrw(), store, menu);
            if (rank == 0) throw missing();
        }
    }

    UUID ensureMenu(UUID store, String name) {
        db.update("insert into store_menus(id, store_id, name) values (?, ?, ?) on conflict do nothing",
            UUID.randomUUID(), store, name);
        return first(db.queryForList("select id from store_menus where store_id = ? and name = ?", UUID.class, store, name));
    }

    Store owned(UUID owner, UUID id) {
        return first(db.query("select id, name, category, latitude, longitude from stores where id = ? and owner_id = ?",
            (rs, row) -> new Store(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("category"),
                rs.getBigDecimal("latitude"), rs.getBigDecimal("longitude")), id, owner));
    }

    void requireMenu(UUID store, UUID menu) {
        first(db.queryForList("select id from store_menus where id = ? and store_id = ?", UUID.class, menu, store));
    }

    record Store(UUID id, String name, String category, BigDecimal latitude, BigDecimal longitude) {}
}

final class GeoDistance {
    private GeoDistance() {}

    static double meters(BigDecimal latitude, BigDecimal longitude, BigDecimal otherLatitude, BigDecimal otherLongitude) {
        double from = Math.toRadians(latitude.doubleValue());
        double to = Math.toRadians(otherLatitude.doubleValue());
        double lat = Math.sin((to - from) / 2);
        double lon = Math.sin(Math.toRadians(otherLongitude.subtract(longitude).doubleValue()) / 2);
        double square = lat * lat + Math.cos(from) * Math.cos(to) * lon * lon;
        return 12_742_008.8 * Math.asin(Math.sqrt(Math.min(1, Math.max(0, square))));
    }
}

@RestController
@Tag(name = "가게 설정", description = "사업자 본인의 가게 정보와 메뉴를 관리합니다.")
@RequestMapping("/api/stores")
class StoreController {
    private final Stores stores;

    StoreController(Stores stores) {
        this.stores = stores;
    }

    @GetMapping("/me")
    @Operation(summary = "내 가게와 메뉴 조회", description = "기존 사업자 본인의 가게 설정과 현재 메뉴를 반환합니다.")
    Map<String, Object> mine(@AuthenticationPrincipal Actor actor) {
        return stores.profile(actor.require(Role.OWNER));
    }

    @PutMapping({"/me", "/{id}"})
    @Operation(summary = "내 가게 설정 저장", description = "가게 이름, 업종, 위치, 첫 방문 혜택을 저장합니다. "
        + "menus를 보내면 현재 메뉴 목록을 바꾸고, 목록에서 빠진 메뉴의 판매 기록은 보존합니다.")
    Map<String, Object> save(@AuthenticationPrincipal Actor actor, @PathVariable(required = false) UUID id,
        @Valid @RequestBody Settings input) {
        return stores.save(actor.require(Role.OWNER), id, input);
    }

    record Settings(@NotBlank @Size(max = 160) String name, @NotBlank @Size(max = 100) String category,
        @Size(max = 300) String roadAddress, @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @Size(max = 300) String firstVisitBenefit, @Size(max = 300) String offerDetails,
        @Size(max = 50) List<@NotNull @Valid Menu> menus) {}

    record Menu(UUID id, @NotBlank @Size(max = 120) String name, @PositiveOrZero Long priceKrw) {}
}
