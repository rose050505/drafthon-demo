package com.v2v.backend;

import java.util.List;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("v2v")
record V2vProperties(
    List<String> corsOrigins,
    String ntsKey,
    String semasKey,
    String buildingKey,
    String kakaoKey,
    String sgisServiceId,
    String sgisSecretKey,
    String catalogToken,
    String aiToken,
    List<UUID> operatorAccountIds
) {}
