package com.v2v.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
    "v2v.sgis-service-id=", "v2v.sgis-secret-key="
})
class WorkflowTest {
    @Autowired WebApplicationContext context;
    @Autowired JsonMapper json;
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
        assertEquals(1, tree(run(get("/api/vacancies"), 200)).size());
        run(body(post("/api/buildings/{id}/opinions", buildingId), Map.of("desiredBusiness", "공방", "reason", "체험 프로그램")), 401);
        run(body(bearer(post("/api/buildings/{id}/opinions", buildingId), student),
            Map.of("desiredBusiness", "공방", "reason", "체험 프로그램")), 201);
        run(catalog(post("/api/integrations/catalog/buildings/{id}/demand-analysis", buildingId)), 202);
        var demand = claim("BUILDING_DEMAND");
        finish(demand, Map.of("themes", List.of("공방")));
        assertEquals(1, tree(run(get("/api/buildings/{id}/demand", buildingId), 200)).size());

        String plan = field(tree(run(body(bearer(post("/api/startup-plans"), founder), Map.of()), 201)), "id");
        run(bearer(post("/api/startup-plans/{id}/analysis", plan), founder), 202);
        var analysis = claim("STARTUP_COMMUNITY");
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
        run(bearer(post("/api/postings/{id}/publish", posting), founder), 200);
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
