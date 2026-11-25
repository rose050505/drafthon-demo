package com.v2v.backend;

import static com.v2v.backend.Api.*;
import static org.springframework.http.HttpStatus.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;

@Service
class Startup {
    private static final String PLAN = """
        select p.id, p.business_idea as "businessIdea", p.preferred_building_id as "preferredBuildingId",
               p.preferred_address as "preferredAddress", p.selected_building_id as "selectedBuildingId",
               p.selected_vacancy_id as "selectedVacancyId", p.selected_industry as "selectedIndustry",
               p.selected_address as "selectedAddress", p.created_at as "createdAt"
        from startup_plans p where p.founder_id = ?
        """;
    private final JdbcTemplate db;
    private final Catalog catalog;
    private final AiJobs ai;
    private final PublicData publicData;
    private final SgisData sgis;

    Startup(JdbcTemplate db, Catalog catalog, AiJobs ai, PublicData publicData, SgisData sgis) {
        this.db = db;
        this.catalog = catalog;
        this.ai = ai;
        this.publicData = publicData;
        this.sgis = sgis;
    }

    Map<String, Object> create(UUID founder, StartupController.NewPlan input) {
        String address = optional(input.preferredAddress());
        if (input.preferredBuildingId() != null) {
            if (address != null) throw invalid("등록된 건물과 직접 입력한 주소 중 하나를 선택하세요.");
            catalog.building(input.preferredBuildingId());
        }
        UUID id = UUID.randomUUID();
        db.update("""
            insert into startup_plans(id, founder_id, business_idea, preferred_building_id, preferred_address)
            values (?, ?, ?, ?, ?)
            """, id, founder, optional(input.businessIdea()), input.preferredBuildingId(), address);
        return get(founder, id);
    }

    Map<String, Object> get(UUID founder, UUID plan) {
        return first(db.queryForList(PLAN + " and p.id = ?", founder, plan));
    }

    List<Map<String, Object>> list(UUID founder, int limit, int offset) {
        return db.queryForList(PLAN + " order by p.created_at desc, p.id limit ? offset ?", founder, limit(limit), offset(offset));
    }

    Map<String, Object> analyze(UUID founder, UUID id, UUID vacancyId) {
        var plan = get(founder, id);
        Map<String, Object> targetVacancy = vacancyId == null ? null : catalog.vacancy(vacancyId);
        UUID building = (UUID) plan.get("preferredBuildingId");
        if (targetVacancy != null) {
            if (!"AVAILABLE".equals(targetVacancy.get("status"))) throw conflict("입점 가능으로 확인된 공실을 선택하세요.");
            building = (UUID) targetVacancy.get("buildingId");
        }
        boolean hasPlace = building != null || plan.get("preferredAddress") != null;
        boolean hasIdea = plan.get("businessIdea") != null;

        JobKind kind;
        if (hasIdea) kind = hasPlace ? JobKind.STARTUP_RISK : JobKind.STARTUP_LOCATION;
        else kind = hasPlace ? JobKind.STARTUP_INDUSTRY : JobKind.STARTUP_COMMUNITY;

        var input = context(founder, plan);
        if (hasPlace) {
            var location = building == null ? publicData.locate((String) plan.get("preferredAddress")) : catalog.building(building);
            input.put("location", location);
            if (targetVacancy != null) input.put("vacancy", targetVacancy);
            if (building != null) input.put("communityDemand", ai.demand(building));
            input.put("nearbyShopQuery", shopQuery(location));
            input.put("areaStatistics", sgis.forLocation((BigDecimal) location.get("longitude"),
                (BigDecimal) location.get("latitude")));
        } else {
            List<Map<String, Object>> candidates = catalog.vacancies(null, 100, 0);
            candidates.forEach(candidate -> candidate.put("nearbyShopQuery", shopQuery(candidate)));
            input.put("vacancies", candidates);
            if (kind == JobKind.STARTUP_COMMUNITY) {
                Map<UUID, List<Map<String, Object>>> analyses = new LinkedHashMap<>();
                for (var vacancy : candidates) {
                    UUID key = (UUID) vacancy.get("buildingId");
                    vacancy.put("communityDemand", analyses.computeIfAbsent(key, ai::demand));
                }
            }
        }
        return ai.queue(founder, kind, id, input);
    }

    Map<String, Object> choose(UUID founder, UUID id, StartupController.Selection input) {
        get(founder, id);
        UUID building = input.buildingId();
        String address = optional(input.address());
        if (input.vacancyId() != null) {
            var vacancy = catalog.vacancy(input.vacancyId());
            UUID vacancyBuilding = (UUID) vacancy.get("buildingId");
            if (!"AVAILABLE".equals(vacancy.get("status"))) throw conflict("입점 가능으로 확인된 공실을 선택하세요.");
            if (building != null && !building.equals(vacancyBuilding)) throw invalid("건물과 공실이 일치하지 않습니다.");
            building = vacancyBuilding;
        }
        if ((building == null) == (address == null)) throw invalid("등록된 건물과 직접 입력한 주소 중 하나를 선택하세요.");
        if (building != null) catalog.building(building);
        db.update("""
            update startup_plans set selected_building_id = ?, selected_vacancy_id = ?, selected_address = ?, selected_industry = ?
            where id = ? and founder_id = ?
            """, building, input.vacancyId(), address, input.industry().strip(), id, founder);
        return get(founder, id);
    }

    Map<String, Object> help(UUID founder, UUID id, StartupController.Chat input) {
        var plan = get(founder, id);
        requireSelection(plan);
        var context = context(founder, plan);
        if (input != null) {
            context.put("message", input.message().strip());
            if (input.previousJobId() != null) {
                var previous = ai.owned(input.previousJobId(), founder);
                if (!id.equals(previous.get("scopeId")) || !"STARTUP_CHAT".equals(previous.get("kind"))
                    || !"COMPLETED".equals(previous.get("status"))) throw invalid("같은 창업 계획의 완료된 상담을 선택하세요.");
                context.put("previousAnswer", previous.get("result"));
            }
        }
        return ai.queue(founder, input == null ? JobKind.STARTUP_FAQ : JobKind.STARTUP_CHAT, id, context);
    }

    static void requireSelection(Map<String, Object> plan) {
        if (plan.get("selectedIndustry") == null) throw conflict("창업 위치와 업종을 먼저 확정하세요.");
    }

    private Map<String, Object> context(UUID founder, Map<String, Object> plan) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("plan", plan);
        properties.put("founderBudgetKrw", db.queryForObject("select founder_budget from accounts where id = ?", Long.class, founder));
        return properties;
    }

    private static Map<String, BigDecimal> shopQuery(Map<String, Object> location) {
        BigDecimal x = (BigDecimal) location.get("longitude");
        BigDecimal y = (BigDecimal) location.get("latitude");
        BigDecimal longitudeRange = new BigDecimal("0.0045");
        BigDecimal latitudeRange = new BigDecimal("0.0036");
        return Map.of("west", x.subtract(longitudeRange).max(BigDecimal.valueOf(-180)),
            "south", y.subtract(latitudeRange).max(BigDecimal.valueOf(-90)),
            "east", x.add(longitudeRange).min(BigDecimal.valueOf(180)),
            "north", y.add(latitudeRange).min(BigDecimal.valueOf(90)));
    }
}

@RestController
@Tag(name = "창업 계획·분석", description = "예비 창업자의 계획 작성, 공실·업종 분석과 상담.")
@RequestMapping("/api/startup-plans")
class StartupController {
    private final Startup startup;

    StartupController(Startup startup) { this.startup = startup; }

    @PostMapping
    @Operation(summary = "창업 계획 만들기",
        description = "예비 창업자의 계획을 만듭니다. 창업 아이템은 선택이고, 위치를 아는 경우 등록된 건물 ID 또는 직접 입력한 주소를 넣습니다. 처음부터 검토할 때는 빈 JSON 객체를 보냅니다.")
    @ResponseStatus(CREATED)
    Map<String, Object> create(@AuthenticationPrincipal Actor actor, @Valid @RequestBody NewPlan input) {
        return startup.create(actor.require(Role.FOUNDER), input);
    }

    @GetMapping
    @Operation(summary = "내 창업 계획 목록 조회",
        description = "로그인한 예비 창업자의 창업 계획을 생성 시각 기준 최신순으로 조회합니다. `limit`과 `offset`으로 목록을 나눠 읽습니다.")
    List<Map<String, Object>> list(@AuthenticationPrincipal Actor actor, @RequestParam(defaultValue = "30") int limit,
        @RequestParam(defaultValue = "0") int offset) {
        return startup.list(actor.require(Role.FOUNDER), limit, offset);
    }

    @GetMapping("/{id}")
    @Operation(summary = "창업 계획 상세 조회",
        description = "창업 계획 ID로 내 아이템, 희망 위치와 확정한 위치·업종 정보를 확인합니다.")
    Map<String, Object> get(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return startup.get(actor.require(Role.FOUNDER), id);
    }

    @PostMapping("/{id}/analysis")
    @Operation(summary = "창업 입지·업종 AI 분석 요청",
        description = "예비 창업자의 아이템과 위치에 따라 위험 분석, 입지 추천 또는 업종 추천을 요청합니다. `vacancyId`를 보내면 선택한 공실과 해당 행정동의 SGIS 통계를 사용합니다. 반환된 작업 ID로 `GET /api/ai-jobs/{id}`를 호출합니다.")
    @ResponseStatus(ACCEPTED)
    Map<String, Object> analyze(@AuthenticationPrincipal Actor actor, @PathVariable UUID id,
        @RequestParam(required = false) UUID vacancyId) {
        return startup.analyze(actor.require(Role.FOUNDER), id, vacancyId);
    }

    @PutMapping("/{id}/selection")
    @Operation(summary = "창업 위치와 업종 확정",
        description = "계획에 선택한 업종 `industry`와 위치를 저장합니다. 공실 ID, 건물 ID, 직접 입력한 주소 중 해당하는 값을 사용합니다.")
    Map<String, Object> choose(@AuthenticationPrincipal Actor actor, @PathVariable UUID id,
        @Valid @RequestBody Selection input) {
        return startup.choose(actor.require(Role.FOUNDER), id, input);
    }

    @PostMapping("/{id}/faq")
    @Operation(summary = "선택 업종의 창업 FAQ 생성 요청",
        description = "위치와 업종 선택 후 해당 업종을 준비할 때 참고할 질문과 답변을 AI에 요청합니다. 결과는 반환된 작업 ID로 조회합니다.")
    @ResponseStatus(ACCEPTED)
    Map<String, Object> faq(@AuthenticationPrincipal Actor actor, @PathVariable UUID id) {
        return startup.help(actor.require(Role.FOUNDER), id, null);
    }

    @PostMapping("/{id}/chat")
    @Operation(summary = "선택 업종 창업 상담 요청",
        description = "확정한 창업안에 대한 질문을 `message`로 보냅니다. 같은 계획의 완료된 상담 작업 ID를 `previousJobId`로 넣으면 해당 답변을 바탕으로 이어서 질문합니다.")
    @ResponseStatus(ACCEPTED)
    Map<String, Object> chat(@AuthenticationPrincipal Actor actor, @PathVariable UUID id, @Valid @RequestBody Chat input) {
        return startup.help(actor.require(Role.FOUNDER), id, input);
    }

    record NewPlan(@Size(max = 500) String businessIdea, UUID preferredBuildingId, @Size(max = 300) String preferredAddress) {}
    record Selection(UUID buildingId, UUID vacancyId, @Size(max = 300) String address, @NotBlank @Size(max = 160) String industry) {}
    record Chat(@NotBlank @Size(max = 1800) String message, UUID previousJobId) {}
}
