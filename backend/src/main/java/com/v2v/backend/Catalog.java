package com.v2v.backend;

import static com.v2v.backend.Api.*;
import static org.springframework.http.HttpStatus.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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

    List<Map<String, Object>> vacancies(UUID building, int limit, int offset, boolean includeUnverified) {
        if (building != null) building(building);
        return db.queryForList(VACANCY + """
            where (? = false or v.building_id = ?) and (v.status = 'AVAILABLE' or (? and v.status = 'UNVERIFIED'))
            order by v.verified_at desc nulls last, v.id limit ? offset ?
            """, building != null, building, includeUnverified, limit(limit), offset(offset));
    }

    List<Map<String, Object>> opinions(UUID id, UUID account, UUID vacancy, int limit, int offset) {
        building(id);
        return db.queryForList("""
            select o.id, o.desired_business as "desiredBusiness", o.category, o.reason, o.created_at as "createdAt",
                o.vacancy_id as "vacancyId",
                (select count(*) from building_opinion_likes l where l.opinion_id = o.id) as "likeCount",
                exists (select 1 from building_opinion_likes l where l.opinion_id = o.id and l.account_id = ?) as "likedByMe"
            from building_opinions o
            where o.building_id = ? and (? = false or o.vacancy_id = ?)
            order by o.created_at desc, o.id desc limit ? offset ?
            """, account, id, vacancy != null, vacancy, limit(limit), offset(offset));
    }

    Map<String, Object> addOpinion(UUID id, UUID author, CatalogController.Opinion input) {
        building(id);
        if (input.vacancyId() != null && !id.equals(vacancy(input.vacancyId()).get("buildingId"))) {
            throw invalid("건물에 속한 공실을 선택하세요.");
        }
        String category = optional(input.category());
        UUID opinion = UUID.randomUUID();
        db.update("""
            insert into building_opinions(id, building_id, author_id, desired_business, reason, category, vacancy_id)
            values (?, ?, ?, ?, ?, ?, ?)
            """, opinion, id, author, input.desiredBusiness().strip(), input.reason().strip(),
            category == null ? "기타" : category, input.vacancyId());
        return Map.of("id", opinion);
    }

    Map<String, Object> opinionLike(UUID building, UUID opinion, UUID account, boolean liked) {
        first(db.queryForList("select id from building_opinions where id = ? and building_id = ?", UUID.class, opinion, building));
        if (liked) {
            db.update("""
                insert into building_opinion_likes(opinion_id, account_id) values (?, ?) on conflict do nothing
                """, opinion, account);
        } else {
            db.update("delete from building_opinion_likes where opinion_id = ? and account_id = ?", opinion, account);
        }
        return db.queryForMap("""
            select count(*) as "likeCount", exists (
                select 1 from building_opinion_likes where opinion_id = ? and account_id = ?
            ) as "likedByMe" from building_opinion_likes where opinion_id = ?
            """, opinion, account, opinion);
    }

    List<Map<String, Object>> demand(UUID id) {
        building(id);
        return ai.demand(id);
    }

    Map<String, Object> analyze(UUID id) {
        var building = building(id);
        var opinions = opinions(id, null, null, 100, 0);
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
@Tag(name = "상권·공실", description = "상권 정보와 공실 조회, 주민 의견, 담당자 데이터 등록.")
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
    @Operation(summary = "지도 영역 안의 상가업소 조회",
        description = "서쪽·동쪽 경도와 남쪽·북쪽 위도 `west`, `east`, `south`, `north`로 범위를 지정합니다. 소진공의 업소명, 업종, 주소, 좌표를 페이지별로 반환합니다.")
    JsonNode shops(@RequestParam BigDecimal west, @RequestParam BigDecimal south, @RequestParam BigDecimal east,
        @RequestParam BigDecimal north, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "30") int size) {
        return publicData.shops(west, south, east, north, page, size);
    }

    @GetMapping("/catalog/building-register")
    @Operation(summary = "건축물대장 표제부 조회",
        description = "시군구 코드 `sigunguCd`, 법정동 코드 `bjdongCd`, 지번 본번 `bun`, 부번 `ji`로 건축물 용도와 면적을 조회합니다. 지번 본번과 부번은 네 자리입니다.")
    JsonNode register(@RequestParam String sigunguCd, @RequestParam String bjdongCd, @RequestParam String bun,
        @RequestParam String ji, @RequestParam(defaultValue = "0") int landType,
        @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "30") int size) {
        return publicData.register(sigunguCd, bjdongCd, bun, ji, landType, page, size);
    }

    @GetMapping("/buildings")
    @Operation(summary = "등록된 건물 목록 조회",
        description = "V2V에 등록된 건물의 ID, 도로명주소, 지도 좌표와 출처를 조회합니다. `limit`과 `offset`으로 페이지를 나눠 읽습니다.")
    List<Map<String, Object>> buildings(@RequestParam(defaultValue = "30") int limit, @RequestParam(defaultValue = "0") int offset) {
        return catalog.buildings(limit, offset);
    }

    @GetMapping({"/vacancies", "/buildings/{id}/vacancies"})
    @Operation(summary = "입점 가능한 공실 목록 조회",
        description = "`/api/vacancies`는 전체 공실을, `/api/buildings/{id}/vacancies`는 지정 건물의 공실을 조회합니다. `status`가 `AVAILABLE`이면 입점 가능, `UNVERIFIED`이면 확인 전입니다.")
    List<Map<String, Object>> vacancies(@PathVariable(required = false) UUID id,
        @RequestParam(defaultValue = "30") int limit, @RequestParam(defaultValue = "0") int offset) {
        return catalog.vacancies(id, limit, offset, true);
    }

    @GetMapping("/buildings/{id}/opinions")
    @Operation(summary = "건물별 주민 의견 조회",
        description = "건물에 등록된 의견과 `category`, `likeCount`, `likedByMe`를 조회합니다. 특정 공실만 보려면 `vacancyId`를 지정합니다. 익명 조회에서는 `likedByMe`가 false입니다.")
    List<Map<String, Object>> opinions(@PathVariable UUID id, @RequestParam(defaultValue = "30") int limit,
        @RequestParam(defaultValue = "0") int offset, @RequestParam(required = false) UUID vacancyId,
        @AuthenticationPrincipal @Nullable Actor actor) {
        return catalog.opinions(id, actor == null ? null : actor.id(), vacancyId, limit, offset);
    }

    @PostMapping("/buildings/{id}/opinions")
    @Operation(summary = "건물에 주민 의견 등록",
        description = "역할 설정 전에도 로그인하면 의견을 등록할 수 있습니다. `desiredBusiness`, `reason`과 선택값 `category`, `vacancyId`를 받습니다.")
    @ResponseStatus(CREATED)
    Map<String, Object> opinion(@PathVariable UUID id, @AuthenticationPrincipal Actor actor, @Valid @RequestBody Opinion data) {
        return catalog.addOpinion(id, actor.id(), data);
    }

    @PutMapping("/buildings/{id}/opinions/{opinionId}/like")
    @Operation(summary = "주민 의견 공감",
        description = "로그인한 계정으로 해당 의견에 공감합니다. 같은 계정의 반복 요청은 한 번으로 계산합니다.")
    Map<String, Object> like(@PathVariable UUID id, @PathVariable UUID opinionId, @AuthenticationPrincipal Actor actor) {
        return catalog.opinionLike(id, opinionId, actor.id(), true);
    }

    @DeleteMapping("/buildings/{id}/opinions/{opinionId}/like")
    @Operation(summary = "주민 의견 공감 취소", description = "본인 계정의 공감을 취소하고 갱신된 전체 공감 수를 반환합니다.")
    Map<String, Object> unlike(@PathVariable UUID id, @PathVariable UUID opinionId, @AuthenticationPrincipal Actor actor) {
        return catalog.opinionLike(id, opinionId, actor.id(), false);
    }

    @GetMapping("/buildings/{id}/demand")
    @Operation(summary = "건물별 AI 수요 분석 결과 조회",
        description = "건물에 등록된 주민 의견을 바탕으로 마지막에 완료된 AI 분석 결과를 조회합니다. 완료된 분석이 없으면 빈 목록을 반환합니다.")
    List<Map<String, Object>> demand(@PathVariable UUID id) { return catalog.demand(id); }

    @PostMapping("/integrations/catalog/buildings")
    @Operation(summary = "운영자용 건물 등록",
        description = "공실 등록에 사용할 건물의 식별값, 도로명주소, 경도·위도와 확인 출처를 저장합니다. 공실 등록 담당자 토큰이 필요합니다.")
    @ResponseStatus(CREATED)
    Map<String, Object> building(@RequestHeader(name = "X-V2V-Catalog-Token", required = false) String token,
        @Valid @RequestBody Building input) {
        integrationToken(properties.catalogToken(), token);
        return catalog.addBuilding(input);
    }

    @PostMapping("/integrations/catalog/buildings/{id}/vacancies")
    @Operation(summary = "운영자용 공실 등록",
        description = "건물 ID 아래에 호실, 공실 상태와 확인 출처를 등록합니다. 입점 가능 등 확인된 상태에는 `verifiedAt`을 입력합니다. 면적, 보증금과 월세는 확인된 값이 있으면 함께 저장합니다.")
    @ResponseStatus(CREATED)
    Map<String, Object> vacancy(@RequestHeader(name = "X-V2V-Catalog-Token", required = false) String token,
        @PathVariable UUID id, @Valid @RequestBody Vacancy input) {
        integrationToken(properties.catalogToken(), token);
        return catalog.addVacancy(id, input);
    }

    @PutMapping("/integrations/catalog/vacancies/{id}")
    @Operation(summary = "운영자용 공실 정보 갱신",
        description = "공실 ID의 호실, 임대 조건, 확인 시각과 상태를 갱신합니다. 공개 공실 목록에는 `AVAILABLE` 상태의 매물이 표시됩니다.")
    Map<String, Object> updateVacancy(@RequestHeader(name = "X-V2V-Catalog-Token", required = false) String token,
        @PathVariable UUID id, @Valid @RequestBody Vacancy input) {
        integrationToken(properties.catalogToken(), token);
        return catalog.updateVacancy(id, input);
    }

    @PostMapping("/integrations/catalog/buildings/{id}/demand-analysis")
    @Operation(summary = "운영자용 주민 수요 분석 요청",
        description = "공실 등록 담당자가 해당 건물에 모인 주민 의견으로 AI 분석 작업을 접수합니다. 완료 결과는 `GET /api/buildings/{id}/demand`에서 확인합니다.")
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

    record Opinion(@NotBlank @Size(max = 160) String desiredBusiness, @NotBlank @Size(max = 1500) String reason,
        @Size(max = 100) String category, UUID vacancyId) {}
}
