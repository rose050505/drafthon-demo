package com.v2v.backend;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:v2v-risk-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "v2v.startup-risk.enabled=true", "v2v.startup-risk.poll-interval-ms=3600000",
    "v2v.ai-token=risk-test-worker", "v2v.catalog-token=risk-test-catalog",
    "v2v.sgis-service-id=", "v2v.sgis-secret-key="
})
class StartupRiskWorkerTest {
    @Autowired WebApplicationContext context;
    @Autowired StartupRiskWorker worker;
    @Autowired JsonMapper json;

    @DynamicPropertySource
    static void configurePython(DynamicPropertyRegistry properties) {
        String defaultPython = System.getProperty("os.name").startsWith("Windows") ? "python" : "python3";
        properties.add("v2v.startup-risk.python",
            () -> System.getenv().getOrDefault("V2V_STARTUP_RISK_PYTHON", defaultPython));
    }

    @Test
    void connectedRiskJobPublishesDongseongroResultAndLeavesOtherJobsForExternalWorker() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
            .addFilters(new DelegatingFilterProxy("springSecurityFilterChain", context)).build();
        JsonNode founder = send(mvc, post("/api/auth/signup"), Map.of(
            "email", "startup-risk@example.test", "displayName", "risk test", "password", "integration-only-password"), 201);
        String authorization = "Bearer " + founder.path("token").asText();
        send(mvc, put("/api/me/role").header("Authorization", authorization),
            Map.of("role", "FOUNDER", "founderBudget", 15_000_000), 200);
        JsonNode building = send(mvc, post("/api/integrations/catalog/buildings")
            .header("X-V2V-Catalog-Token", "risk-test-catalog"), Map.of(
            "buildingKey", "test-dongseongro", "roadAddress", "대구 중구 동성로", "longitude", 128.5957,
            "latitude", 35.869, "source", "Test database only"), 201);
        String buildingId = building.path("id").asText();
        JsonNode vacancy = send(mvc, post("/api/integrations/catalog/buildings/{id}/vacancies", buildingId)
            .header("X-V2V-Catalog-Token", "risk-test-catalog"), Map.of(
            "unitLabel", "101", "status", "AVAILABLE", "source", "Test database only", "areaM2", 35,
            "depositKrw", 20_000_000, "monthlyRentKrw", 950_000, "verifiedAt", "2020-01-01T00:00:00Z"), 201);
        JsonNode plan = send(mvc, post("/api/startup-plans").header("Authorization", authorization),
            Map.of("businessIdea", "테이크아웃 커피숍을 운영합니다."), 201);
        JsonNode risk = send(mvc, post("/api/startup-plans/{id}/analysis", plan.path("id").asText())
            .header("Authorization", authorization).param("vacancyId", vacancy.path("id").asText()), null, 202);
        assertEquals("STARTUP_RISK", risk.path("kind").asText());

        var lookup = get("/api/ai-jobs/{id}", risk.path("id").asText()).header("Authorization", authorization);
        assertEquals("QUEUED", send(mvc, lookup, null, 200).path("status").asText());
        String claimPath = "/api/integrations/ai/jobs/claim";
        send(mvc, post(claimPath).header("X-V2V-AI-Token", "risk-test-worker"), null, 204);
        JsonNode ideaSearch = send(mvc, post("/api/startup-plans").header("Authorization", authorization),
            Map.of(), 201);
        send(mvc, post("/api/startup-plans/{id}/analysis", ideaSearch.path("id").asText())
            .header("Authorization", authorization), null, 202);

        worker.processNext();

        JsonNode completed = send(mvc, lookup, null, 200);
        assertEquals("COMPLETED", completed.path("status").asText());
        JsonNode result = completed.path("result");
        assertEquals("analyzed", result.path("analysisStatus").asText());
        assertEquals(300, result.path("radiusM").asInt());
        assertTrue(result.path("studentProjects").isArray());
        assertFalse(result.path("studentProjects").isEmpty());
        JsonNode row = result.path("candidates").get(0);
        assertEquals(41, row.path("public_comparison").path("same_industry_count").asInt());
        assertEquals("high", row.path("risk_assessment").path("status").asText());
        assertTrue(row.path("lease").path("monthly_total_krw").path("candidate").isNull());
        assertEquals("unavailable", row.path("learned_risk").path("status").asText());
        JsonNode external = send(mvc, post(claimPath).header("X-V2V-AI-Token", "risk-test-worker"), null, 200);
        assertEquals("STARTUP_COMMUNITY", external.path("kind").asText());
    }

    private JsonNode send(MockMvc mvc, MockHttpServletRequestBuilder request, Object value, int code) throws Exception {
        if (value != null) request.contentType("application/json").content(json.writeValueAsString(value));
        var response = mvc.perform(request).andExpect(status().is(code)).andReturn().getResponse();
        return code == 204 ? null : json.readTree(response.getContentAsByteArray());
    }
}
