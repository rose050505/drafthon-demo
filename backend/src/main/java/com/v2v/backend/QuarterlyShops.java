package com.v2v.backend;

import static com.v2v.backend.Api.*;
import static org.springframework.http.HttpStatus.CREATED;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@Service
class QuarterlyShops {
    private static final BigDecimal LATITUDE_WINDOW = new BigDecimal("0.001");
    private static final int RADIUS_METERS = 100;
    private final JdbcTemplate db;
    private final Stores stores;

    QuarterlyShops(JdbcTemplate db, Stores stores) {
        this.db = db;
        this.stores = stores;
    }

    @Transactional
    Map<String, Object> importQuarter(String key, AreaChangeController.Snapshot input) {
        Quarter.parse(key);
        var unique = new HashSet<String>();
        var rows = input.shops().stream().map(shop -> {
            String shopKey = shop.shopKey().strip();
            if (!unique.add(shopKey)) throw invalid("같은 분기 파일 안에 업소 번호가 중복됩니다.");
            return new Object[]{key, shopKey, shop.name().strip(), shop.category().strip(),
                optional(shop.buildingKey()), shop.longitude(), shop.latitude()};
        }).toList();
        if (db.update("insert into shop_quarters(quarter, source, coverage) values (?, ?, ?) on conflict do nothing",
            key, input.source().strip(), input.coverage().strip()) == 0) {
            throw conflict("이미 등록된 분기 자료입니다.");
        }
        db.batchUpdate("""
            insert into quarterly_shops(quarter, shop_key, name, category, building_key, longitude, latitude)
            values (?, ?, ?, ?, ?, ?, ?)
            """, rows);
        return Map.of("quarter", key, "insertedShopCount", rows.size());
    }

    @Transactional(readOnly = true)
    Map<String, Object> lastChange(UUID owner, UUID id, String key) {
        var store = stores.owned(owner, id);
        List<String> quarters = db.queryForList("select quarter from shop_quarters order by quarter desc", String.class);
        String target = key == null ? quarters.stream().findFirst().orElse(null) : key;
        var result = new LinkedHashMap<String, Object>();
        result.put("availableQuarters", quarters);
        result.put("radiusMeters", RADIUS_METERS);
        result.put("quarter", target);
        if (target == null) {
            result.put("status", "INSUFFICIENT_DATA");
            return result;
        }
        Quarter current = Quarter.parse(target);
        Quarter previous = Quarter.of(current.start().minusMonths(3));
        result.put("previousQuarter", previous.key());
        var missing = List.of(previous.key(), current.key()).stream().filter(quarter -> !quarters.contains(quarter)).toList();
        if (!missing.isEmpty()) {
            result.put("status", "INSUFFICIENT_DATA");
            result.put("missingQuarters", missing);
            return result;
        }
        var sources = db.queryForList("""
            select quarter, source, coverage, imported_at as "importedAt"
            from shop_quarters where quarter in (?, ?) order by quarter
            """, previous.key(), current.key());
        result.put("sources", sources);
        if (!sources.getFirst().get("coverage").equals(sources.getLast().get("coverage"))) {
            result.put("status", "COVERAGE_MISMATCH");
            return result;
        }
        var before = shops(store, previous.key());
        var after = shops(store, current.key());
        result.put("status", "AVAILABLE");
        result.put("category", store.category());
        result.put("previousSameCategoryCount", before.stream().filter(row -> store.category().equalsIgnoreCase((String) row.get("category"))).count());
        result.put("currentSameCategoryCount", after.stream().filter(row -> store.category().equalsIgnoreCase((String) row.get("category"))).count());
        result.put("newShops", changed(after, before));
        result.put("disappearedShops", changed(before, after));
        result.put("newVacancies", vacancies(store, current));
        return result;
    }

    private List<Map<String, Object>> shops(Stores.Store store, String quarter) {
        var candidates = db.queryForList("""
            select shop_key as "shopKey", name, category, building_key as "buildingKey", latitude, longitude
            from quarterly_shops where quarter = ? and latitude between ? and ? order by name, shop_key
            """, quarter, store.latitude().subtract(LATITUDE_WINDOW), store.latitude().add(LATITUDE_WINDOW));
        return withinRadius(store, candidates);
    }

    private List<Map<String, Object>> vacancies(Stores.Store store, Quarter current) {
        var candidates = db.queryForList("""
            select v.id, v.building_id as "buildingId", v.unit_label as "unitLabel", v.status, v.registered_at as "registeredAt",
                   b.road_address as "roadAddress", b.latitude, b.longitude
            from vacancies v join buildings b on b.id = v.building_id
            where b.latitude between ? and ? and v.registered_at >= ? and v.registered_at < ?
                and v.status in ('AVAILABLE', 'UNVERIFIED') order by v.registered_at, v.id
            """, store.latitude().subtract(LATITUDE_WINDOW), store.latitude().add(LATITUDE_WINDOW),
            current.start().atStartOfDay(StoreSales.KOREA).toOffsetDateTime(),
            current.start().plusMonths(3).atStartOfDay(StoreSales.KOREA).toOffsetDateTime());
        return withinRadius(store, candidates);
    }

    private static List<Map<String, Object>> withinRadius(Stores.Store store, List<Map<String, Object>> candidates) {
        return candidates.stream()
            .filter(row -> GeoDistance.meters(store.latitude(), store.longitude(), (BigDecimal) row.get("latitude"),
                (BigDecimal) row.get("longitude")) <= RADIUS_METERS)
            .toList();
    }

    private static List<Map<String, Object>> changed(List<Map<String, Object>> from, List<Map<String, Object>> against) {
        Set<Object> ids = against.stream().map(row -> row.get("shopKey")).collect(Collectors.toSet());
        Set<List<Object>> aliases = against.stream().filter(row -> row.get("buildingKey") != null)
            .map(QuarterlyShops::identity).collect(Collectors.toSet());
        return from.stream().filter(row -> !ids.contains(row.get("shopKey"))
            && (row.get("buildingKey") == null || !aliases.contains(identity(row)))).toList();
    }

    private static List<Object> identity(Map<String, Object> row) {
        return List.of(row.get("buildingKey"), ((String) row.get("name")).replaceAll("[\\s\\p{Punct}·]+", "")
            .toLowerCase(java.util.Locale.ROOT));
    }

    private record Quarter(LocalDate start) {
        static Quarter parse(String key) {
            if (!key.matches("20[0-9]{2}-Q[1-4]")) throw invalid("분기는 YYYY-Q1 형식으로 지정하세요.");
            int quarter = key.charAt(6) - '0';
            return new Quarter(LocalDate.of(Integer.parseInt(key.substring(0, 4)), (quarter - 1) * 3 + 1, 1));
        }

        static Quarter of(LocalDate date) {
            int month = (date.getMonthValue() - 1) / 3 * 3 + 1;
            return new Quarter(LocalDate.of(date.getYear(), month, 1));
        }

        String key() {
            return start.getYear() + "-Q" + ((start.getMonthValue() - 1) / 3 + 1);
        }
    }
}

@RestController
@Tag(name = "분기별 상권 변화", description = "소진공 분기 자료를 등록하고 가게 주변 업소의 변화를 비교합니다.")
@RequestMapping("/api")
class AreaChangeController {
    private final QuarterlyShops shops;
    private final V2vProperties config;

    AreaChangeController(QuarterlyShops shops, V2vProperties config) {
        this.shops = shops;
        this.config = config;
    }

    @GetMapping("/stores/{id}/area-change")
    @Operation(summary = "내 가게 주변 두 분기의 상권 변화 조회", description = "q에 YYYY-Q1 형식의 분기를 지정하거나 최신 등록 분기를 사용합니다. "
        + "직전 분기와 비교해 반경 100m 안 신규·사라진 업소와 새로 등록된 공실을 반환합니다. 사라진 업소는 폐업 확정이 아닙니다.")
    Map<String, Object> compare(@AuthenticationPrincipal Actor actor, @PathVariable UUID id,
        @RequestParam(required = false) String q) {
        return shops.lastChange(actor.require(Role.OWNER), id, q);
    }

    @PutMapping("/integrations/catalog/quarterly-shops/{quarter}")
    @ResponseStatus(CREATED)
    @Operation(summary = "운영자용 소진공 상가 분기 파일 등록", description = "소진공 상가 자료를 변환한 JSON 한 분기분을 등록합니다. "
        + "동일 범위의 분기 자료를 비교할 수 있으며, 출처와 자료 범위를 저장합니다.")
    Map<String, Object> upload(@RequestHeader(name = "X-V2V-Catalog-Token", required = false) String token,
        @PathVariable String quarter, @Valid @RequestBody Snapshot input) {
        integrationToken(config.catalogToken(), token);
        return shops.importQuarter(quarter, input);
    }

    record Snapshot(@NotBlank @Size(max = 250) String source, @NotBlank @Size(max = 100) String coverage,
        @NotEmpty @Size(max = 20_000) List<@NotNull @Valid Shop> shops) {}
    record Shop(@NotBlank @Size(max = 100) String shopKey, @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 100) String category, @Size(max = 100) String buildingKey,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
        @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude) {}
}
