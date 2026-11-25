package com.v2v.backend;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

@Service
class SgisData {
    private static final String BASE_URL = "https://sgisapi.mods.go.kr/OpenAPI3/";
    private final PublicData publicData;
    private final V2vProperties config;
    private String accessToken;
    private Instant reuseUntil = Instant.EPOCH;

    SgisData(PublicData publicData, V2vProperties config) {
        this.publicData = publicData;
        this.config = config;
    }

    Map<String, Object> forLocation(BigDecimal longitude, BigDecimal latitude) {
        var statistics = new LinkedHashMap<String, Object>();
        statistics.put("source", "SGIS");
        statistics.put("geographicScope", "ADMINISTRATIVE_DONG");
        if (Api.optional(config.sgisServiceId()) == null || Api.optional(config.sgisSecretKey()) == null) {
            statistics.put("status", "NOT_CONFIGURED");
            return statistics;
        }

        String token = token();
        if (token == null) {
            statistics.put("status", "UNAVAILABLE");
            return statistics;
        }
        JsonNode area = result("addr/rgeocodewgs84.json", Map.of("accessToken", token, "x_coor", longitude,
            "y_coor", latitude, "addr_type", 20)).path(0);
        // SGIS uses an eight-digit code built from its own province, district, and dong codes.
        String areaCode = area.path("sido_cd").asText() + area.path("sgg_cd").asText() + area.path("emdong_cd").asText();
        if (!areaCode.matches("[0-9]{8}")) {
            statistics.put("status", "UNAVAILABLE");
            return statistics;
        }
        statistics.put("administrativeDongCode", areaCode);
        statistics.put("administrativeDongName", area.path("emdong_nm").asText());

        JsonNode years = result("year/data.json", Map.of("accessToken", token));
        int censusYear = Math.min(years.path("lin_yr").asInt(0), years.path("lcorp_yr").asInt(0));
        var areaQuery = Map.of("accessToken", token, "adm_cd", areaCode);
        JsonNode census = censusYear < 2000 ? null : matchingRow(result("stats/population.json", Map.of(
            "accessToken", token, "adm_cd", areaCode, "low_search", 0, "year", censusYear)), areaCode);
        if (census != null) {
            statistics.put("censusYear", censusYear);
            statistics.put("censusSummary", census);
        }
        JsonNode ageProfile = matchingRow(result("startupbiz/pplsummary.json", areaQuery), areaCode);
        if (ageProfile != null) statistics.put("residentAgeProfile", ageProfile);

        var industryYears = new ArrayList<JsonNode>();
        for (JsonNode year : result("startupbiz/corpindecrease.json", areaQuery)) {
            JsonNode counts = year.path("theme_list");
            if (areaCode.equals(year.path("adm_cd").asText()) && year.path("year").asInt(0) >= 2000
                && counts.isArray() && !counts.isEmpty()) {
                industryYears.add(year);
            }
        }
        if (!industryYears.isEmpty()) {
            statistics.put("industryBusinessCounts", industryYears.stream()
                .sorted(Comparator.comparingInt((JsonNode year) -> year.path("year").asInt()).reversed())
                .limit(3).toList());
        }
        int present = (census == null ? 0 : 1) + (ageProfile == null ? 0 : 1) + (industryYears.isEmpty() ? 0 : 1);
        statistics.put("status", switch (present) {
            case 3 -> "AVAILABLE";
            case 0 -> "UNAVAILABLE";
            default -> "PARTIAL";
        });
        return statistics;
    }

    private synchronized String token() {
        Instant now = Instant.now();
        if (accessToken != null && now.isBefore(reuseUntil)) return accessToken;
        JsonNode auth = result("auth/authentication.json", Map.of("consumer_key", config.sgisServiceId().strip(),
            "consumer_secret", config.sgisSecretKey().strip()));
        accessToken = Api.optional(auth.path("accessToken").asText());
        reuseUntil = now.plusSeconds(300);
        return accessToken;
    }

    private JsonNode result(String endpoint, Map<String, ?> query) {
        try {
            JsonNode response = publicData.getJson(BASE_URL + endpoint, query);
            if ("0".equals(response.path("errCd").asText())) return response.path("result");
        } catch (ResponseStatusException ignored) {
            // Keep the analysis usable when this supplementary source is unavailable.
        }
        return MissingNode.getInstance();
    }

    private static JsonNode matchingRow(JsonNode rows, String areaCode) {
        for (JsonNode row : rows) {
            if (areaCode.equals(row.path("adm_cd").asText())) return row;
        }
        return null;
    }
}
