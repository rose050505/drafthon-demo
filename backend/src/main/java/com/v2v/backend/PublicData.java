package com.v2v.backend;

import static com.v2v.backend.Api.*;
import static org.springframework.http.HttpStatus.*;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
class PublicData {
    private static final String NTS = "https://api.odcloud.kr/api/nts-businessman/v1/status";
    private static final String SEMAS = "https://apis.data.go.kr/B553077/api/open/sdsc2/storeListInRectangle";
    private static final String REGISTER = "https://apis.data.go.kr/1613000/BldRgstHubService/getBrTitleInfo";
    private static final String KAKAO_ADDRESS = "https://dapi.kakao.com/v2/local/search/address.json";
    private final RestClient client;
    private final JsonMapper json;
    private final V2vProperties config;

    PublicData(JsonMapper json, V2vProperties config) {
        this.json = json;
        this.config = config;
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(4));
        requests.setReadTimeout(Duration.ofSeconds(8));
        this.client = RestClient.builder().requestFactory(requests).build();
    }

    void requireActiveBusiness(String number) {
        var payload = json.createObjectNode();
        payload.putArray("b_no").add(number);
        JsonNode result = send(NTS, config.ntsKey(), Map.of("returnType", "JSON"), payload);
        JsonNode records = result.path("data");
        if (!records.isArray()) throw upstream();
        for (JsonNode record : records) {
            if (number.equals(record.path("b_no").asText())) {
                if ("01".equals(record.path("b_stt_cd").asText())) return;
                throw error(UNPROCESSABLE_ENTITY, "계속사업자로 조회되는 사업자등록번호가 필요합니다.");
            }
        }
        throw error(UNPROCESSABLE_ENTITY, "등록된 사업자 정보를 확인할 수 없습니다.");
    }

    JsonNode shops(BigDecimal west, BigDecimal south, BigDecimal east, BigDecimal north, int page, int size) {
        if (west.compareTo(east) >= 0 || south.compareTo(north) >= 0 || west.abs().compareTo(BigDecimal.valueOf(180)) > 0
            || east.abs().compareTo(BigDecimal.valueOf(180)) > 0 || south.abs().compareTo(BigDecimal.valueOf(90)) > 0
            || north.abs().compareTo(BigDecimal.valueOf(90)) > 0 || page < 1) {
            throw invalid("지도 좌표 범위와 페이지 번호를 확인하세요.");
        }
        JsonNode result = send(SEMAS, config.semasKey(), Map.of("minx", west, "miny", south, "maxx", east, "maxy", north,
            "pageNo", page, "numOfRows", limit(size), "type", "json"), null);
        JsonNode body = result.path("body");
        String code = result.path("header").path("resultCode").asText();
        if (!body.isObject() || !("00".equals(code) || "0".equals(code))) throw upstream();
        return result;
    }

    JsonNode register(String sigungu, String dong, String bun, String ji, int landType, int page, int size) {
        if (!sigungu.matches("[0-9]{5}") || !dong.matches("[0-9]{5}") || !bun.matches("[0-9]{4}")
            || !ji.matches("[0-9]{4}") || landType < 0 || landType > 2 || page < 1) {
            throw invalid("법정동 코드와 지번, 대지 구분을 확인하세요.");
        }
        var result = send(REGISTER, config.buildingKey(), Map.of("sigunguCd", sigungu, "bjdongCd", dong, "bun", bun, "ji", ji,
            "platGbCd", landType, "_type", "json", "pageNo", page, "numOfRows", limit(size)), null).path("response");
        if (!"00".equals(result.path("header").path("resultCode").asText()) || !result.path("body").isObject()) throw upstream();
        return result.path("body");
    }

    Map<String, Object> locate(String address) {
        if (config.kakaoKey() == null || config.kakaoKey().isBlank()) {
            throw error(SERVICE_UNAVAILABLE, "직접 입력한 주소를 분석하려면 카카오 REST API 키가 필요합니다.");
        }
        String query = URLEncoder.encode(address, StandardCharsets.UTF_8);
        var request = client.get().uri(URI.create(KAKAO_ADDRESS + "?query=" + query + "&size=2&analyze_type=exact"))
            .header("Authorization", "KakaoAK " + config.kakaoKey().strip());
        JsonNode documents = request(request).path("documents");
        if (!documents.isArray()) throw upstream();
        if (documents.isEmpty()) throw error(UNPROCESSABLE_ENTITY, "주소를 찾을 수 없습니다. 전체 도로명 주소를 입력하세요.");
        if (documents.size() != 1) throw error(UNPROCESSABLE_ENTITY, "주소가 여러 건 조회됩니다. 전체 도로명 주소를 입력하세요.");
        var found = documents.get(0);
        try {
            return Map.of("address", found.path("address_name").asText(), "longitude", new BigDecimal(found.path("x").asText()),
                "latitude", new BigDecimal(found.path("y").asText()), "source", "KAKAO_LOCAL");
        } catch (NumberFormatException exception) {
            throw upstream();
        }
    }

    private JsonNode send(String endpoint, String key, Map<String, ?> parameters, JsonNode payload) {
        if (key == null || key.isBlank()) throw error(SERVICE_UNAVAILABLE, "공공데이터 API 인증키가 설정되지 않았습니다.");
        Map<String, Object> query = new LinkedHashMap<>(parameters);
        query.put(SEMAS.equals(endpoint) ? "ServiceKey" : "serviceKey", key.strip());
        if (payload != null) {
            return request(client.post().uri(queryUri(endpoint, query)).contentType(MediaType.APPLICATION_JSON)
                .body(json.writeValueAsString(payload)));
        }
        return getJson(endpoint, query);
    }

    JsonNode getJson(String endpoint, Map<String, ?> parameters) {
        return request(client.get().uri(queryUri(endpoint, parameters)));
    }

    private static URI queryUri(String endpoint, Map<String, ?> parameters) {
        String encoded = parameters.entrySet().stream()
            .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue().toString(), StandardCharsets.UTF_8))
            .collect(Collectors.joining("&"));
        return URI.create(endpoint + "?" + encoded);
    }

    private JsonNode request(RestClient.RequestHeadersSpec<?> request) {
        try {
            String response = request.accept(MediaType.APPLICATION_JSON).retrieve().body(String.class);
            JsonNode parsed = response == null ? null : json.readTree(response);
            if (parsed == null || !parsed.isObject()) throw upstream();
            return parsed;
        } catch (RestClientException | tools.jackson.core.JacksonException e) {
            throw upstream();
        }
    }

    private static org.springframework.web.server.ResponseStatusException upstream() {
        return error(BAD_GATEWAY, "공공데이터 제공기관 응답을 확인할 수 없습니다.");
    }
}
