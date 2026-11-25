package com.v2v.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SgisDataTest {
    private static final String BASE_URL = "https://sgisapi.mods.go.kr/OpenAPI3/";
    private static final String CODE = "22010530";
    private static final Map<String, String> AREA = Map.of("accessToken", "test-token", "adm_cd", CODE);
    private final JsonMapper json = JsonMapper.builder().build();
    private final PublicData http = mock(PublicData.class);
    private final SgisData sgis = new SgisData(http,
        new V2vProperties(List.of(), "", "", "", "", "test-id", "test-key", "", "", List.of(), null));
    private final BigDecimal longitude = new BigDecimal("128.595");
    private final BigDecimal latitude = new BigDecimal("35.867");

    @Test
    void selectsTheRequestedDongAndPreservesTheDataYears() {
        stub("auth/authentication.json", Map.of("consumer_key", "test-id", "consumer_secret", "test-key"),
            """
            {"accessToken":"test-token"}
            """);
        stub("addr/rgeocodewgs84.json", Map.of("accessToken", "test-token", "x_coor", longitude,
            "y_coor", latitude, "addr_type", 20), """
            [{"sido_cd":"22","sgg_cd":"010","emdong_cd":"530","emdong_nm":"테스트 행정동"}]
            """);
        stub("year/data.json", Map.of("accessToken", "test-token"), """
            {"lin_yr":"2025","lcorp_yr":"2024"}
            """);
        stub("stats/population.json", Map.of("accessToken", "test-token", "adm_cd", CODE, "low_search", 0, "year", 2024), """
            [{"adm_cd":"22010","tot_ppltn":"900"},{"adm_cd":"22010530","tot_ppltn":"100","employee_cnt":"80"}]
            """);
        stub("startupbiz/pplsummary.json", AREA, """
            [{"adm_cd":"22010","twenty_per":"10.0"},{"adm_cd":"22010530","twenty_per":"30.0"}]
            """);
        stub("startupbiz/corpindecrease.json", AREA, """
            [{"adm_cd":"22010530","year":"2025","theme_list":[]},
             {"adm_cd":"22010530","year":"2024","theme_list":[{"theme_nm":"꽃가게","corp_cnt":4}]},
             {"adm_cd":"22010","year":"2025","theme_list":[{"theme_nm":"꽃가게","corp_cnt":100}]},
             {"adm_cd":"22010530","year":"2023","theme_list":[{"theme_nm":"꽃가게","corp_cnt":2}]},
             {"adm_cd":"22010530","year":"2022","theme_list":[{"theme_nm":"꽃가게","corp_cnt":3}]},
             {"adm_cd":"22010530","year":"2021","theme_list":[{"theme_nm":"꽃가게","corp_cnt":4}]}]
            """);

        var stats = sgis.forLocation(longitude, latitude);
        assertEquals("AVAILABLE", stats.get("status"));
        assertEquals(CODE, stats.get("administrativeDongCode"));
        assertEquals(2024, stats.get("censusYear"));
        assertEquals("100", ((JsonNode) stats.get("censusSummary")).path("tot_ppltn").asText());
        assertEquals("30.0", ((JsonNode) stats.get("residentAgeProfile")).path("twenty_per").asText());
        assertEquals(List.of("2024", "2023", "2022"), ((List<?>) stats.get("industryBusinessCounts")).stream()
            .map(row -> ((JsonNode) row).path("year").asText()).toList());

        when(http.getJson(BASE_URL + "startupbiz/pplsummary.json", AREA)).thenReturn(json.readTree("{\"errCd\":-100}"));
        var partial = sgis.forLocation(longitude, latitude);
        assertEquals("PARTIAL", partial.get("status"));
        assertFalse(partial.containsKey("residentAgeProfile"));
    }

    private void stub(String endpoint, Map<String, ?> query, String result) {
        when(http.getJson(BASE_URL + endpoint, query)).thenReturn(json.readTree("{\"errCd\":0,\"result\":" + result + "}"));
    }
}
