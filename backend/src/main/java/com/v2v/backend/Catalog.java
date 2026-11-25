package com.v2v.backend;

import static com.v2v.backend.Api.*;
import static org.springframework.http.HttpStatus.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@Service
class Catalog {
    private static final String BUILDING = """
        select id, building_key as "buildingKey", name, road_address as "roadAddress", longitude, latitude,
               main_use as "mainUse", source, registered_at as "registeredAt" from buildings
        """;
    private static final String VACANCY = """
        select v.id, v.building_id as "buildingId", v.unit_label as "unitLabel", v.area_m2 as "areaM2",
               v.deposit_krw as "depositKrw", v.monthly_rent_krw as "monthlyRentKrw", v.status, v.source,
               v.verified_at as "verifiedAt", b.road_address as "roadAddress", b.longitude, b.latitude
        from vacancies v join buildings b on b.id = v.building_id
        """;
    private final JdbcTemplate db;
    private final AiJobs ai;

    Catalog(JdbcTemplate db, AiJobs ai) {
        this.db = db;
        this.ai = ai;
    }

    List<Map<String, Object>> buildings(int limit, int offset) {
        return db.queryForList(BUILDING + " order by road_address, id limit ? offset ?", limit(limit), offset(offset));
    }

    Map<String, Object> building(UUID id) {
        return first(db.queryForList(BUILDING + " where id = ?", id));
    }

    Map<String, Object> vacancy(UUID id) {
        return first(db.queryForList(VACANCY + " where v.id = ?", id));
    }

    List<Map<String, Object>> vacancies(UUID building, int limit, int offset) {
        if (building != null) building(building);
        return db.queryForList(VACANCY + """
            where (? = false or v.building_id = ?) and v.status = 'AVAILABLE'
            order by v.verified_at desc, v.id limit ? offset ?
            """, building != null, building, limit(limit), offset(offset));
    }

    List<Map<String, Object>> opinions(UUID id, int limit, int offset) {
        building(id);
        return db.queryForList("""
            select id, desired_business as "desiredBusiness", reason, created_at as "createdAt"
            from building_opinions where building_id = ? order by created_at desc, id desc limit ? offset ?
            """, id, limit(limit), offset(offset));
    }

    Map<String, Object> addOpinion(UUID id, UUID author, CatalogController.Opinion input) {
        building(id);
        UUID opinion = UUID.randomUUID();
        db.update("""
            insert into building_opinions(id, building_id, author_id, desired_business, reason) values (?, ?, ?, ?, ?)
            """, opinion, id, author, input.desiredBusiness().strip(), input.reason().strip());
        return Map.of("id", opinion);
    }

    List<Map<String, Object>> demand(UUID id) {
        building(id);
        return ai.demand(id);
    }

    Map<String, Object> analyze(UUID id) {
        var building = building(id);
        var opinions = opinions(id, 100, 0);
        if (opinions.isEmpty()) throw conflict("분석할 주민 의견이 없습니다.");
        int total = db.queryForObject("select count(*) from building_opinions where building_id = ?", Integer.class, id);
        return ai.queue(null, JobKind.BUILDING_DEMAND, id,
            Map.of("building", building, "opinions", opinions, "totalOpinionCount", total));
    }

    Map<String, Object> addBuilding(CatalogController.Building input) {
        UUID id = UUID.randomUUID();
        db.update("""
            insert into buildings(id, building_key, name, road_address, longitude, latitude, main_use, source)
            values (?, ?, ?, ?, ?, ?, ?, ?)
            """, id, input.buildingKey().strip(), optional(input.name()), input.roadAddress().strip(), input.longitude(),
            input.latitude(), optional(input.mainUse()), input.source().strip());
        return building(id);
    }

    Map<String, Object> addVacancy(UUID id, CatalogController.Vacancy input) {
        building(id);
        checkVerification(input.status(), input.verifiedAt());
        UUID vacancy = UUID.randomUUID();
        db.update("""
            insert into vacancies(id, building_id, unit_label, area_m2, deposit_krw, monthly_rent_krw, status, source,
                                  verified_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, vacancy, id, input.unitLabel().strip(), input.areaM2(), input.depositKrw(), input.monthlyRentKrw(),
            input.status().name(), input.source().strip(), input.verifiedAt());
        return vacancy(vacancy);
    }

    Map<String, Object> updateVacancy(UUID id, CatalogController.Vacancy input) {
        checkVerification(input.status(), input.verifiedAt());
        int updated = db.update("""
            update vacancies set unit_label = ?, area_m2 = ?, deposit_krw = ?, monthly_rent_krw = ?, status = ?, source = ?,
                verified_at = ? where id = ?
            """, input.unitLabel().strip(), input.areaM2(), input.depositKrw(), input.monthlyRentKrw(),
            input.status().name(), input.source().strip(), input.verifiedAt(), id);
        if (updated == 0) throw missing();
        return vacancy(id);
    }

    private static void checkVerification(CatalogController.VacancyStatus status, OffsetDateTime date) {
        if (status != CatalogController.VacancyStatus.UNVERIFIED && date == null) throw invalid("공실 확인 시각을 입력하세요.");
        if (date != null && date.isAfter(Accounts.now())) throw invalid("공실 확인 시각은 현재 이전이어야 합니다.");
    }
}

@RestController
@RequestMapping("/api")
class CatalogController {
    private final Catalog catalog;
    private final PublicData publicData;
    private final V2vProperties properties;

    CatalogController(Catalog catalog, PublicData publicData, V2vProperties properties) {
        this.catalog = catalog;
        this.publicData = publicData;
        this.properties = properties;
    }

    @GetMapping("/catalog/shops")
    JsonNode shops(@RequestParam BigDecimal west, @RequestParam BigDecimal south, @RequestParam BigDecimal east,
        @RequestParam BigDecimal north, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "30") int size) {
        return publicData.shops(west, south, east, north, page, size);
    }

    @GetMapping("/catalog/building-register")
    JsonNode register(@RequestParam String sigunguCd, @RequestParam String bjdongCd, @RequestParam String bun,
        @RequestParam String ji, @RequestParam(defaultValue = "0") int landType,
        @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "30") int size) {
        return publicData.register(sigunguCd, bjdongCd, bun, ji, landType, page, size);
    }

    @GetMapping("/buildings")
    List<Map<String, Object>> buildings(@RequestParam(defaultValue = "30") int limit, @RequestParam(defaultValue = "0") int offset) {
        return catalog.buildings(limit, offset);
    }

    @GetMapping({"/vacancies", "/buildings/{id}/vacancies"})
    List<Map<String, Object>> vacancies(@PathVariable(required = false) UUID id,
        @RequestParam(defaultValue = "30") int limit, @RequestParam(defaultValue = "0") int offset) {
        return catalog.vacancies(id, limit, offset);
    }

    @GetMapping("/buildings/{id}/opinions")
    List<Map<String, Object>> opinions(@PathVariable UUID id, @RequestParam(defaultValue = "30") int limit,
        @RequestParam(defaultValue = "0") int offset) {
        return catalog.opinions(id, limit, offset);
    }

    @PostMapping("/buildings/{id}/opinions")
    @ResponseStatus(CREATED)
    Map<String, Object> opinion(@PathVariable UUID id, @AuthenticationPrincipal Actor actor, @Valid @RequestBody Opinion data) {
        return catalog.addOpinion(id, actor.id(), data);
    }

    @GetMapping("/buildings/{id}/demand")
    List<Map<String, Object>> demand(@PathVariable UUID id) { return catalog.demand(id); }

    @PostMapping("/integrations/catalog/buildings")
    @ResponseStatus(CREATED)
    Map<String, Object> building(@RequestHeader(name = "X-V2V-Catalog-Token", required = false) String token,
        @Valid @RequestBody Building input) {
        integrationToken(properties.catalogToken(), token);
        return catalog.addBuilding(input);
    }

    @PostMapping("/integrations/catalog/buildings/{id}/vacancies")
    @ResponseStatus(CREATED)
    Map<String, Object> vacancy(@RequestHeader(name = "X-V2V-Catalog-Token", required = false) String token,
        @PathVariable UUID id, @Valid @RequestBody Vacancy input) {
        integrationToken(properties.catalogToken(), token);
        return catalog.addVacancy(id, input);
    }

    @PutMapping("/integrations/catalog/vacancies/{id}")
    Map<String, Object> updateVacancy(@RequestHeader(name = "X-V2V-Catalog-Token", required = false) String token,
        @PathVariable UUID id, @Valid @RequestBody Vacancy input) {
        integrationToken(properties.catalogToken(), token);
        return catalog.updateVacancy(id, input);
    }

    @PostMapping("/integrations/catalog/buildings/{id}/demand-analysis")
    @ResponseStatus(ACCEPTED)
    Map<String, Object> analyze(@RequestHeader(name = "X-V2V-Catalog-Token", required = false) String token, @PathVariable UUID id) {
        integrationToken(properties.catalogToken(), token);
        return catalog.analyze(id);
    }

    enum VacancyStatus { UNVERIFIED, AVAILABLE, IN_NEGOTIATION, CLOSED }

    record Building(@NotBlank @Size(max = 100) String buildingKey, @Size(max = 200) String name,
        @NotBlank @Size(max = 300) String roadAddress, @NotNull @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
        @NotNull @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude, @Size(max = 150) String mainUse,
        @NotBlank @Size(max = 250) String source) {}

    record Vacancy(@NotBlank @Size(max = 100) String unitLabel, @Positive @Digits(integer = 10, fraction = 2) BigDecimal areaM2,
        @PositiveOrZero Long depositKrw, @PositiveOrZero Long monthlyRentKrw, @NotNull VacancyStatus status,
        @NotBlank @Size(max = 250) String source, OffsetDateTime verifiedAt) {}

    record Opinion(@NotBlank @Size(max = 160) String desiredBusiness, @NotBlank @Size(max = 1500) String reason) {}
}
