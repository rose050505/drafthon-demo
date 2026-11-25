package com.v2v.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:v2v-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "v2v.ai-token=workflow-local-test",
    "v2v.catalog-token=workflow-catalog-test",
    "v2v.nts-key=", "v2v.semas-key=", "v2v.building-key=", "v2v.kakao-key=",
    "v2v.sgis-service-id=", "v2v.sgis-secret-key=",
    "v2v.operator-account-ids=00000000-0000-4000-8000-000000000001"
})
class WorkflowTest {
    @Autowired WebApplicationContext context;
    @Autowired JsonMapper json;
    @Autowired JdbcTemplate db;
    MockMvc mvc;

    @BeforeEach
    void prepare() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(new DelegatingFilterProxy("springSecurityFilterChain", context)).build();
    }

    @Test
    void founderPublishesAndStudentCompletesProject() throws Exception {
        String founder = register("founder", Map.of("role", "FOUNDER", "founderBudget", 15_000_000));
        String student = register("student", Map.of("role", "STUDENT"));
        String other = register("other", Map.of("role", "STUDENT"));
        var building = tree(run(body(catalog(post("/api/integrations/catalog/buildings")), Map.of("buildingKey", "test-register",
            "roadAddress", "Integration test", "longitude", 128.59, "latitude", 35.87, "source", "Test database only")), 201));
        String buildingId = field(building, "id");
        assertTrue(tree(run(get("/api/vacancies"), 200)).isEmpty());
        var vacancy = Map.of("unitLabel", "101", "status", "AVAILABLE", "source", "Test database only",
            "verifiedAt", "2020-01-01T00:00:00Z");
        String vacancyId = field(tree(run(body(catalog(post("/api/integrations/catalog/buildings/{id}/vacancies", buildingId)), vacancy), 201)), "id");
        run(body(catalog(post("/api/integrations/catalog/buildings/{id}/vacancies", buildingId)),
            Map.of("unitLabel", "102", "status", "UNVERIFIED", "source", "Test database only")), 201);
        var listedVacancies = tree(run(get("/api/vacancies"), 200));
        assertEquals(2, listedVacancies.size());
        assertEquals("UNVERIFIED", field(listedVacancies.path(1), "status"));
        var residentAccount = tree(run(body(post("/api/auth/signup"), Map.of(
            "email", "resident@example.test", "username", "review-resident", "displayName", "resident",
            "password", "integration-only-password")), 201));
        String resident = field(tree(run(body(post("/api/auth/login"),
            Map.of("username", "REVIEW-RESIDENT", "password", "integration-only-password")), 200)), "token");
        assertEquals("review-resident", field(residentAccount.path("account"), "username"));
        run(body(post("/api/buildings/{id}/opinions", buildingId), Map.of("desiredBusiness", "공방", "reason", "체험 프로그램")), 401);
        String opinion = field(tree(run(body(bearer(post("/api/buildings/{id}/opinions", buildingId), resident),
            Map.of("desiredBusiness", "공방", "reason", "체험 프로그램", "category", "문화", "vacancyId", vacancyId)), 201)), "id");
        String likePath = "/api/buildings/{id}/opinions/{opinionId}/like";
        run(put(likePath, buildingId, opinion), 401);
        run(bearer(put(likePath, buildingId, opinion), resident), 200);
        assertEquals(1, tree(run(bearer(put(likePath, buildingId, opinion), resident), 200)).path("likeCount").asInt());
        var liked = tree(run(bearer(get("/api/buildings/{id}/opinions", buildingId), resident), 200)).path(0);
        assertEquals("문화", field(liked, "category"));
        assertTrue(liked.path("likedByMe").asBoolean());
        assertFalse(tree(run(get("/api/buildings/{id}/opinions", buildingId), 200)).path(0).path("likedByMe").asBoolean());
        assertEquals(0, tree(run(bearer(delete(likePath, buildingId, opinion), resident), 200)).path("likeCount").asInt());
        run(bearer(put(likePath, buildingId, opinion), resident), 200);
        run(catalog(post("/api/integrations/catalog/buildings/{id}/demand-analysis", buildingId)), 202);
        var demand = claim("BUILDING_DEMAND");
        finish(demand, Map.of("themes", List.of("공방")));
        assertEquals(1, tree(run(get("/api/buildings/{id}/demand", buildingId), 200)).size());

        String plan = field(tree(run(body(bearer(post("/api/startup-plans"), founder), Map.of()), 201)), "id");
        run(bearer(post("/api/startup-plans/{id}/analysis", plan), founder), 202);
        var analysis = claim("STARTUP_COMMUNITY");
        assertEquals(1, analysis.path("input").path("vacancies").size());
        assertEquals(vacancyId, field(analysis.path("input").path("vacancies").get(0), "id"));
        assertTrue(analysis.path("input").path("vacancies").get(0).path("nearbyShopQuery").isObject());
        finish(analysis, Map.of("recommendations", List.of()));
        run(bearer(get("/api/ai-jobs/{id}", field(analysis, "id")), other), 404);
        run(bearer(post("/api/startup-plans/{id}/analysis", plan).param("vacancyId", vacancyId), founder), 202);
        var vacancyAnalysis = claim("STARTUP_INDUSTRY");
        assertEquals(vacancyId, field(vacancyAnalysis.path("input").path("vacancy"), "id"));
        assertEquals("NOT_CONFIGURED", field(vacancyAnalysis.path("input").path("areaStatistics"), "status"));
        finish(vacancyAnalysis, Map.of("recommendations", List.of()));
        run(body(bearer(put("/api/startup-plans/{id}/selection", plan), founder),
            Map.of("vacancyId", vacancyId, "industry", "공방")), 200);

        String posting = field(tree(run(body(bearer(post("/api/postings"), founder), Map.of(
            "startupPlanId", plan, "title", "상품 패키지 개선", "interest", "디자인", "workDescription", "디자인 시안 제작",
            "deliverable", "시안 파일", "periodWeeks", 16, "stipendKrw", 400000, "availability", "금요일")), 201)), "id");
        run(get("/api/postings/{id}", posting), 404);
        run(bearer(post("/api/postings/{id}/draft-generation", posting), founder), 202);
        finish(claim("POSTING_DRAFT"), Map.of("title", "상품 패키지 개선"));
        run(bearer(post("/api/postings/{id}/publish", posting), other), 403);
        run(bearer(post("/api/postings/{id}/publish", posting), founder), 409);
        run(bearer(post("/api/postings/{id}/review", posting), founder), 200);
        String operations = operator();
        run(bearer(get("/api/operations/posting-reviews"), founder), 403);
        var pending = tree(run(bearer(get("/api/operations/posting-reviews"), operations), 200)).path(0);
        assertEquals(posting, field(pending, "id"));
        assertEquals(1, pending.path("opinionCount").asInt());
        assertEquals(1, pending.path("likeCount").asInt());
        var decision = put("/api/operations/postings/{id}/review", posting);
        run(body(bearer(decision, operations),
            Map.of("status", "NEXT_OPPORTUNITY", "readinessScore", 40, "taskFitScore", 80)), 200);
        run(bearer(post("/api/postings/{id}/publish", posting), founder), 409);
        run(bearer(post("/api/postings/{id}/review", posting), founder), 200);
        var approved = tree(run(body(bearer(put("/api/operations/postings/{id}/review", posting), operations),
            Map.of("status", "SELECTED", "readinessScore", 90, "taskFitScore", 95)), 200));
        assertEquals(1, approved.path("reviewLikeCount").asInt());
        run(bearer(post("/api/postings/{id}/publish", posting), founder), 200);
        run(body(bearer(put("/api/postings/{id}/advisor", posting), founder),
            Map.of("advisorName", "지도교수")), 200);
        assertEquals(1, tree(run(get("/api/postings").param("interest", "디자인"), 200)).size());
        assertFalse(tree(run(get("/api/postings/{id}", posting), 200)).has("startupPlanId"));

        run(body(bearer(put("/api/students/me"), student),
            Map.of("university", "Integration test", "interests", "디자인", "availability", "금요일")), 200);
        var pdf = new MockMultipartFile("file", "proof.pdf", "application/pdf", "%PDF-1.7".getBytes(StandardCharsets.US_ASCII));
        run(multipart(org.springframework.http.HttpMethod.PUT, "/api/students/me/documents/ENROLLMENT").file(pdf)
            .header("Authorization", "Bearer " + student), 204);
        run(bearer(get("/api/students/me/documents/ENROLLMENT"), other), 404);
        String jobId = field(tree(run(bearer(post("/api/postings/{id}/application-questions", posting), student), 202)), "id");
        var questions = claim("APPLICATION_QUESTIONS");
        assertEquals(jobId, field(questions, "id"));
        finish(questions, Map.of("questions", List.of("가능한 일정은?", "참여 이유는?")));
        run(body(bearer(post("/api/postings/{id}/applications", posting), other),
            Map.of("questionJobId", jobId, "answers", List.of("금요일", "배우고 싶습니다."))), 409);
        var application = Map.of("questionJobId", jobId, "answers", List.of("금요일", "배우고 싶습니다."));
        String applicant = field(tree(run(body(bearer(post("/api/postings/{id}/applications", posting), student), application), 201)), "id");
        run(body(bearer(post("/api/postings/{id}/applications", posting), student), application), 409);
        assertEquals(1, tree(run(bearer(get("/api/postings/{id}/applications", posting), founder), 200)).size());
        String match = field(tree(run(bearer(post("/api/postings/{id}/applications/{applicationId}/match", posting, applicant), founder), 201)), "id");
        assertTrue(tree(run(bearer(get("/api/students/me/career-cards"), student), 200)).isEmpty());
        run(bearer(post("/api/matches/{id}/start", match), founder), 200);
        run(body(bearer(post("/api/matches/{id}/complete", match), founder), Map.of("handoverNotes", "작업 파일 인수인계 완료")), 200);
        assertEquals(1, tree(run(bearer(get("/api/students/me/career-cards"), student), 200)).size());
        run(bearer(get("/api/matches/{id}", match), other), 404);
        for (int i = 0; i < 4; i++) {
            String extra = register("extra" + i, Map.of("role", "STUDENT"));
            run(body(bearer(put("/api/students/me"), extra),
                Map.of("university", "Integration test", "interests", "디자인", "availability", "금요일")), 200);
            UUID extraId = UUID.fromString(field(tree(run(bearer(get("/api/me"), extra), 200)), "id"));
            UUID applicationId = UUID.randomUUID();
            db.update("""
                insert into applications(id, posting_id, student_id, answers_json, status) values (?, ?, ?, '[]', 'SUBMITTED')
                """, applicationId, UUID.fromString(posting), extraId);
            var selected = bearer(post("/api/postings/{id}/applications/{applicationId}/match", posting, applicationId), founder);
            if (i < 3) assertEquals("지도교수", field(tree(run(selected, 201)), "advisorName"));
            else run(selected, 409);
            if (i == 2) {
                var filled = tree(run(get("/api/postings/{id}", posting), 200));
                assertEquals(4, filled.path("matchedStudentCount").asInt());
                assertEquals("MATCHED", field(filled, "status"));
            }
        }
    }

    @Test
    void accountCanSwitchRolesAndCannotSelfAssignOperator() throws Exception {
        String account = register("multi", Map.of("role", "FOUNDER", "founderBudget", 900000));
        run(body(bearer(put("/api/me/role"), account), Map.of("role", "OPERATOR")), 403);
        var enrolled = tree(run(body(bearer(put("/api/me/role"), account), Map.of("role", "STUDENT")), 200));
        assertEquals(2, enrolled.path("roles").size());
        assertEquals("STUDENT", field(enrolled, "role"));
        var switched = tree(run(body(bearer(put("/api/me/role"), account), Map.of("role", "FOUNDER")), 200));
        assertEquals(900000, switched.path("founderBudget").asInt());
        assertEquals("FOUNDER", field(switched, "role"));
    }

    private String operator() throws Exception {
        String password = "integration-only-password";
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(password);
        db.update("insert into accounts(id, email, password_hash, display_name) values (?, ?, ?, ?) on conflict do nothing",
            UUID.fromString("00000000-0000-4000-8000-000000000001"), "operator@example.test", hash, "operator");
        String token = field(tree(run(body(post("/api/auth/login"),
            Map.of("email", "operator@example.test", "password", password)), 200)), "token");
        run(body(bearer(put("/api/me/role"), token), Map.of("role", "OPERATOR")), 200);
        return token;
    }

    @Test
    void storeRespondsToResidentsTracksSalesAndSharesOnlyConsentedMonthlyData() throws Exception {
        var storeOwner = storeOwner("store-owner");
        var outsider = storeOwner("store-outsider");
        var resident = member("store-resident");
        var supporter = member("store-supporter");
        var muted = member("store-muted");
        var week = StoreSales.today();
        String month = YearMonth.from(week).toString();
        var profile = tree(run(body(bearer(put("/api/stores/me"), storeOwner), Map.of("name", "테스트 가게", "category", "카페",
            "latitude", 35.8710, "longitude", 128.6000, "firstVisitBenefit", "첫 방문 안내",
            "menus", List.of(Map.of("name", "새 메뉴", "priceKrw", 8_000)))), 200));
        String store = field(profile, "id");
        String menu = field(profile.path("menus").path(0), "id");
        run(bearer(get("/api/stores/{id}/nearby-wishes", store), outsider), 404);
        run(bearer(get("/api/stores/{id}/sales", store).param("from", week.toString()).param("to", week.toString()), outsider), 404);
        String outside = building("shop-test-far", 35.8731);
        String farther = opinion(outside, resident);
        assertEquals(farther, field(tree(run(bearer(get("/api/stores/{id}/nearby-wishes", store), storeOwner), 200)).path(0), "id"));
        String near = building("shop-test-near", 35.8714);
        String wish = opinion(near, resident);
        run(bearer(put("/api/buildings/{id}/opinions/{opinionId}/like", near, wish), supporter), 200);
        run(bearer(put("/api/buildings/{id}/opinions/{opinionId}/like", near, wish), muted), 200);
        run(body(bearer(put("/api/me/alerts/preferences"), muted), Map.of("enabled", false)), 204);
        var nearby = tree(run(bearer(get("/api/stores/{id}/nearby-wishes", store), storeOwner), 200));
        assertEquals(1, nearby.size());
        assertEquals(wish, field(nearby.path(0), "id"));
        run(body(bearer(post("/api/stores/{id}/answer-draft", store), storeOwner), Map.of("wishId", wish)), 202);
        var draft = claim("STORE_ANSWER_DRAFT");
        assertEquals(wish, field(draft.path("input").path("request"), "id"));
        finish(draft, Map.of("message", "판매를 준비하겠습니다"));
        var response = Map.of("storeId", store, "menuId", menu, "message", "판매를 준비하겠습니다");
        String answer = field(tree(run(body(bearer(post("/api/wishes/{id}/answers", wish), storeOwner), response), 201)), "id");
        run(body(bearer(post("/api/wishes/{id}/answers", wish), storeOwner), response), 409);
        run(get("/api/wishes/{id}/answers", wish), 200);
        assertEquals(1, tree(run(get("/api/buildings/{id}/opinions", near), 200)).path(0).path("answerCount").asInt());
        run(bearer(post("/api/answers/{id}/went", answer), resident), 409);
        assertEquals(1, tree(run(bearer(get("/api/me/alerts"), resident), 200)).size());
        assertEquals(1, tree(run(bearer(get("/api/me/alerts"), supporter), 200)).size());
        assertTrue(tree(run(bearer(get("/api/me/alerts"), muted), 200)).isEmpty());
        run(bearer(get("/api/answers/{id}/show", answer), muted), 404);
        run(bearer(get("/api/answers/{id}/show", answer), supporter), 200);
        run(body(bearer(patch("/api/answers/{id}", answer), outsider), Map.of("status", "SELLING")), 404);
        run(body(bearer(patch("/api/answers/{id}", answer), storeOwner), Map.of("status", "SELLING")), 200);
        run(body(bearer(put("/api/answers/{id}/reviews/me", answer), supporter), Map.of("rating", 4, "comment", "후기")), 403);
        run(bearer(post("/api/answers/{id}/went", answer), resident), 200);
        assertEquals(1, tree(run(bearer(post("/api/answers/{id}/went", answer), resident), 200)).path("visitCount").asInt());
        run(body(bearer(put("/api/answers/{id}/reviews/me", answer), resident), Map.of("rating", 4, "comment", "이용 후기")), 200);
        var reviews = tree(run(get("/api/answers/{id}/reviews", answer), 200));
        assertEquals(1, reviews.size());
        assertFalse(reviews.path(0).has("accountId"));
        run(bearer(post("/api/stores/{id}/review-analysis", store), storeOwner), 202);
        var reviewAnalysis = claim("STORE_REVIEW_ANALYSIS");
        assertEquals("이용 후기", field(reviewAnalysis.path("input").path("reviews").path(0), "comment"));
        finish(reviewAnalysis, Map.of("themes", List.of("이용 후기")));

        String ingredient = field(tree(run(body(bearer(post("/api/stores/{id}/ingredients", store), storeOwner),
            Map.of("name", "재료", "unit", "g", "onHand", 50, "packQuantity", 100, "leadDays", 1)), 201)), "id");
        run(body(bearer(put("/api/stores/{id}/menus/{menuId}/ingredients/{ingredientId}", store, menu, ingredient), storeOwner),
            Map.of("quantityPerSale", 10)), 204);
        assertEquals("INSUFFICIENT_DATA", field(tree(run(bearer(get("/api/stores/{id}/stock-recommendations", store), storeOwner), 200)).path(0), "status"));
        for (int day = 1; day <= 3; day++) {
            run(body(bearer(put("/api/stores/{id}/menus/{menuId}/days/{date}", store, menu, week.minusDays(day)), storeOwner),
                Map.of("soldQuantity", 10, "soldOut", day == 1)), 200);
        }
        var forecast = tree(run(bearer(get("/api/stores/{id}/stock-recommendations", store), storeOwner), 200)).path(0);
        assertEquals(110, forecast.path("tomorrowRequired").asInt());
        assertEquals(200, forecast.path("recommendedOrderQuantity").asInt());
        assertTrue(forecast.path("soldOutInReferencePeriod").asBoolean());
        String order = field(tree(run(body(bearer(post("/api/stores/{id}/orders", store), storeOwner),
            Map.of("ingredientId", ingredient, "quantity", 100, "arrivesOn", week.plusDays(1).toString())), 201)), "id");
        assertEquals(100, tree(run(bearer(get("/api/stores/{id}/stock-recommendations", store), storeOwner), 200)).path(0)
            .path("recommendedOrderQuantity").asInt());
        for (int retry = 0; retry < 2; retry++) {
            run(body(bearer(put("/api/stores/{id}/orders/{orderId}/status", store, order), storeOwner), Map.of("status", "RECEIVED")), 200);
        }
        assertEquals(150, tree(run(bearer(get("/api/stores/{id}/ingredients", store), storeOwner), 200)).path(0).path("onHand").asInt());

        run(body(bearer(post("/api/answers/{id}/days", answer), storeOwner),
            Map.of("date", week.toString(), "soldQuantity", 6, "salesKrw", 48000, "soldOut", false, "shownVisits", 2)), 200);
        var followUp = tree(run(body(bearer(put("/api/stores/{id}/follow-ups/{month}", store, month), storeOwner),
            Map.of("businessStatus", "OPERATING", "salesBand", "UNDER_1M")), 200));
        assertEquals(2, followUp.path("visits").path("shownVisits").asInt());
        assertEquals(1, followUp.path("visits").path("uniqueSelfReportedVisitors").asInt());
        assertEquals(48000, followUp.path("sales").path("salesKrw").asInt());
        run(body(bearer(put("/api/stores/{id}/sharing", store), storeOwner),
            Map.of("shareStatus", true, "shareSales", false, "shareVisits", true)), 204);
        run(bearer(get("/api/track").param("term", month), outsider), 403);
        String operatorToken = operator();
        var tracked = tree(run(bearer(get("/api/track").param("term", month), operatorToken), 200)).path(0);
        assertEquals("OPERATING", field(tracked, "businessStatus"));
        assertTrue(tracked.path("soldQuantity").isNull());
        assertFalse(tracked.has("salesKrw"));
        assertEquals(2, tracked.path("shownVisits").asInt());
        run(body(bearer(post("/api/answers/{id}/report", answer), supporter), Map.of("reason", "확인이 필요한 답변")), 204);
        assertEquals(answer, field(tree(run(bearer(get("/api/operations/answer-reports"), operatorToken), 200)).path(0), "answerId"));
        run(body(bearer(put("/api/operations/answers/{id}/visibility", answer), operatorToken), Map.of("hidden", true)), 204);
        assertTrue(tree(run(get("/api/wishes/{id}/answers", wish), 200)).isEmpty());
        run(get("/api/answers/{id}/reviews", answer), 404);
    }

    @Test
    void comparesConsecutiveQuarterSnapshotsWithoutCallingAChangedShopIdAClosure() throws Exception {
        String owner = storeOwner("quarter-owner");
        String store = field(tree(run(body(bearer(put("/api/stores/me"), owner), Map.of("name", "분기 비교 가게",
            "category", "카페", "latitude", 35.8700, "longitude", 128.5940)), 200)), "id");
        assertEquals("INSUFFICIENT_DATA", field(tree(run(bearer(get("/api/stores/{id}/area-change", store), owner), 200)), "status"));
        var stable = Map.of("shopKey", "before-stable", "name", "카페", "category", "카페", "buildingKey", "same-building",
            "latitude", 35.8701, "longitude", 128.5940);
        var changedId = Map.of("shopKey", "after-stable", "name", "카 페", "category", "카페", "buildingKey", "same-building",
            "latitude", 35.8701, "longitude", 128.5940);
        var gone = Map.of("shopKey", "gone", "name", "이전 업소", "category", "카페", "latitude", 35.8699, "longitude", 128.5940);
        var added = Map.of("shopKey", "new", "name", "다음 업소", "category", "식당", "latitude", 35.8702, "longitude", 128.5940);
        for (var quarter : List.of("2026-Q2", "2026-Q3")) {
            var data = quarter.endsWith("2") ? List.of(stable, gone) : List.of(changedId, added);
            var body = Map.of("source", "테스트 자료", "coverage", "동성로", "shops", data);
            run(body(put("/api/integrations/catalog/quarterly-shops/{quarter}", quarter), body), 401);
            run(body(catalog(put("/api/integrations/catalog/quarterly-shops/{quarter}", quarter)), body), 201);
            run(body(catalog(put("/api/integrations/catalog/quarterly-shops/{quarter}", quarter)), body), 409);
        }
        var comparison = tree(run(bearer(get("/api/stores/{id}/area-change", store).param("q", "2026-Q3"), owner), 200));
        assertEquals("AVAILABLE", field(comparison, "status"));
        assertEquals(2, comparison.path("previousSameCategoryCount").asInt());
        assertEquals(1, comparison.path("currentSameCategoryCount").asInt());
        assertEquals(1, comparison.path("newShops").size());
        assertEquals("new", field(comparison.path("newShops").path(0), "shopKey"));
        assertEquals(1, comparison.path("disappearedShops").size());
        assertEquals("gone", field(comparison.path("disappearedShops").path(0), "shopKey"));
    }

    private String member(String name) throws Exception {
        return field(signup(name), "token");
    }

    private String storeOwner(String name) throws Exception {
        var account = signup(name);
        UUID id = UUID.fromString(field(account.path("account"), "id"));
        db.update("""
            update accounts set active_role = 'OWNER', business_number = '1234567890',
                business_status_checked_at = current_timestamp where id = ?
            """, id);
        db.update("insert into account_roles(account_id, role) values (?, 'OWNER')", id);
        return field(account, "token");
    }

    private JsonNode signup(String name) throws Exception {
        return tree(run(body(post("/api/auth/signup"), Map.of("email", name + "@example.test",
            "displayName", name, "password", "integration-only-password")), 201));
    }

    private String building(String key, double latitude) throws Exception {
        return field(tree(run(body(catalog(post("/api/integrations/catalog/buildings")), Map.of("buildingKey", key,
            "roadAddress", "Test-only address", "longitude", 128.6000, "latitude", latitude, "source", "Test only")), 201)), "id");
    }

    private String opinion(String building, String resident) throws Exception {
        return field(tree(run(body(bearer(post("/api/buildings/{id}/opinions", building), resident),
            Map.of("desiredBusiness", "카페", "reason", "동네 요청", "category", "카페")), 201)), "id");
    }

    @Test
    void swaggerShowsEndpointsAndTheirAuthentication() throws Exception {
        run(get("/swagger-ui.html"), 302);
        run(get("/swagger-ui/index.html"), 200);
        run(get("/swagger-ui/swagger-ui-bundle.js"), 200);
        run(get("/v3/api-docs/swagger-config"), 200);

        var docs = tree(run(get("/v3/api-docs"), 200));
        var schemes = docs.path("components").path("securitySchemes");
        assertEquals("bearer", schemes.path("BearerAuth").path("scheme").asText());
        assertEquals("X-V2V-AI-Token", schemes.path("X-V2V-AI-Token").path("name").asText());
        assertEquals("X-V2V-Catalog-Token", schemes.path("X-V2V-Catalog-Token").path("name").asText());
        var paths = docs.path("paths");
        assertFalse(paths.path("/api/health").path("get").has("security"));
        for (var path : paths.properties()) {
            for (String method : List.of("get", "post", "put", "delete", "patch", "head", "options", "trace")) {
                var operation = path.getValue().path(method);
                if (!operation.isObject()) continue;
                String endpoint = method + " " + path.getKey();
                assertFalse(operation.path("summary").asText("").isBlank(), endpoint + " summary");
                assertFalse(operation.path("description").asText("").isBlank(), endpoint + " description");
            }
        }
        var analysis = paths.path("/api/startup-plans/{id}/analysis").path("post");
        assertEquals("창업 입지·업종 AI 분석 요청", analysis.path("summary").asText());
        assertTrue(analysis.path("description").asText().contains("vacancyId"));
        assertTrue(analysis.path("security").path(0).has("BearerAuth"));
        var worker = docs.path("paths").path("/api/integrations/ai/jobs/claim").path("post");
        assertTrue(worker.path("security").path(0).has("X-V2V-AI-Token"));
        var catalog = docs.path("paths").path("/api/integrations/catalog/buildings").path("post");
        assertTrue(catalog.path("security").path(0).has("X-V2V-Catalog-Token"));
        run(post("/api/integrations/ai/jobs/claim"), 401);
        run(get("/api/me"), 401);
    }

    @Test
    void upstreamKeysAreRequiredForPublicDataAndOwnerActivation() throws Exception {
        run(get("/api/catalog/shops").param("west", "128.59").param("east", "128.60").param("south", "35.86")
            .param("north", "35.88"), 503);
        run(get("/api/catalog/building-register").param("sigunguCd", "27110").param("bjdongCd", "10100")
            .param("bun", "0001").param("ji", "0000"), 503);
        var signed = tree(run(body(post("/api/auth/signup"), Map.of("email", "owner@example.test",
            "displayName", "owner", "password", "integration-only-password")), 201));
        String token = field(signed, "token");
        run(body(bearer(put("/api/me/role"), token), Map.of("role", "OWNER", "businessNumber", "1234567890")), 503);
        assertTrue(tree(run(bearer(get("/api/me"), token), 200)).path("role").isNull());
    }

    private String register(String name, Map<String, ?> role) throws Exception {
        var account = tree(run(body(post("/api/auth/signup"), Map.of("email", name + "@example.test",
            "displayName", name, "password", "integration-only-password")), 201));
        String token = field(account, "token");
        run(body(bearer(put("/api/me/role"), token), role), 200);
        return token;
    }

    private JsonNode claim(String kind) throws Exception {
        var job = tree(run(post("/api/integrations/ai/jobs/claim").header("X-V2V-AI-Token", "workflow-local-test"), 200));
        assertEquals(kind, field(job, "kind"));
        return job;
    }

    private void finish(JsonNode job, Map<String, ?> result) throws Exception {
        run(body(put("/api/integrations/ai/jobs/{id}/result", field(job, "id"))
            .header("X-V2V-AI-Token", "workflow-local-test"),
            Map.of("status", "COMPLETED", "leaseId", field(job, "leaseId"), "result", result)), 204);
    }

    private MockHttpServletRequestBuilder catalog(MockHttpServletRequestBuilder request) {
        return request.header("X-V2V-Catalog-Token", "workflow-catalog-test");
    }

    private MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private MockHttpServletRequestBuilder body(MockHttpServletRequestBuilder request, Object value) {
        return request.contentType("application/json").content(json.writeValueAsString(value));
    }

    private MvcResult run(RequestBuilder request, int code) throws Exception {
        return mvc.perform(request).andExpect(status().is(code)).andReturn();
    }

    private JsonNode tree(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String field(JsonNode entry, String key) {
        assertTrue(entry.hasNonNull(key), key);
        return entry.path(key).asText();
    }
}
