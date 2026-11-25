package com.v2v.backend;

import static com.v2v.backend.Api.*;
import static org.springframework.http.HttpStatus.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.OncePerRequestFilter;

enum Role { FOUNDER, OWNER, STUDENT, OPERATOR }

record Actor(UUID id, Role role, String tokenHash) {
    UUID require(Role... allowed) {
        if (role == null || !List.of(allowed).contains(role)) throw error(FORBIDDEN, "이 역할에서는 사용할 수 없습니다.");
        return id;
    }
}

@Configuration
class SecurityConfig {
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, Accounts accounts, V2vProperties config) {
        return http.csrf(csrf -> csrf.disable())
            .cors(cors -> cors.configurationSource(request -> {
                CorsConfiguration policy = new CorsConfiguration();
                policy.setAllowedOrigins(config.corsOrigins() == null ? List.of() : config.corsOrigins());
                policy.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
                policy.setAllowedHeaders(List.of("Authorization", "Content-Type"));
                return policy;
            }))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            .exceptionHandling(errors -> errors.authenticationEntryPoint((req, res, ex) -> res.sendError(401)))
            .authorizeHttpRequests(requests -> requests
                .requestMatchers("/error", "/api/health", "/api/auth/signup", "/api/auth/login", "/api/integrations/**")
                .permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/catalog/**", "/api/buildings",
                    "/api/buildings/*/vacancies", "/api/buildings/*/opinions", "/api/buildings/*/demand",
                    "/api/vacancies", "/api/postings", "/api/postings/*",
                    "/api/wishes/*/answers", "/api/answers/*/reviews",
                    "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {
                    String header = req.getHeader("Authorization");
                    if (header != null && header.startsWith("Bearer ")) {
                        Actor actor = accounts.authenticate(header.substring(7));
                        if (actor != null) {
                            SecurityContextHolder.getContext().setAuthentication(
                                new UsernamePasswordAuthenticationToken(actor, null, List.of()));
                        }
                    }
                    chain.doFilter(req, res);
                }
            }, AnonymousAuthenticationFilter.class)
            .build();
    }
}

@Service
class Accounts {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder();
    private final JdbcTemplate db;
    private final String unknownPasswordHash = BCRYPT.encode(newToken());

    private final V2vProperties config;

    Accounts(JdbcTemplate db, V2vProperties config) {
        this.db = db;
        this.config = config;
    }

    @Transactional
    Session signup(AuthController.Signup data) {
        passwordLength(data.password());
        UUID id = UUID.randomUUID();
        db.update("insert into accounts(id, email, password_hash, display_name, username) values (?, ?, ?, ?, ?)",
            id, normalize(data.email()), BCRYPT.encode(data.password()), data.displayName().strip(),
            data.username() == null ? null : normalize(data.username()));
        return session(id);
    }

    @Transactional
    Session login(AuthController.Credentials data) {
        passwordLength(data.password());
        String email = optional(data.email());
        String username = optional(data.username());
        if ((email == null) == (username == null)) throw invalid("이메일 또는 아이디 중 하나를 입력하세요.");
        List<CredentialsRow> results = db.query("select id, password_hash from accounts where email = ? or username = ?",
            (rs, row) -> new CredentialsRow(rs.getObject("id", UUID.class), rs.getString("password_hash")),
            email == null ? null : normalize(email), username == null ? null : normalize(username));
        CredentialsRow account = results.isEmpty() ? null : results.getFirst();
        boolean valid = BCRYPT.matches(data.password(), account == null ? unknownPasswordHash : account.hash());
        if (!valid || account == null) throw error(UNAUTHORIZED, "로그인 정보와 비밀번호를 확인하세요.");
        return session(account.id());
    }

    Actor authenticate(String token) {
        if (!token.matches("[A-Za-z0-9_-]{43}")) return null;
        String hash = digest(token);
        return db.query("""
            select a.id, a.active_role as role from access_tokens t join accounts a on a.id = t.account_id
            where t.token_hash = ? and t.expires_at > current_timestamp
            """, (rs, row) -> {
                UUID id = rs.getObject("id", UUID.class);
                String role = activeRole(id, rs.getString("role"));
                return new Actor(id, role == null ? null : Role.valueOf(role), hash);
            }, hash)
            .stream().findFirst().orElse(null);
    }

    Map<String, Object> profile(UUID id) {
        var profile = first(db.queryForList("""
            select id, email, username, display_name as "displayName", active_role as role, founder_budget as "founderBudget",
                   business_number as "businessNumber", business_status_checked_at as "businessStatusCheckedAt"
            from accounts where id = ?
            """, id));
        var roles = new ArrayList<>(db.queryForList("""
            select role from account_roles where account_id = ? and role <> 'OPERATOR' order by role
            """, String.class, id));
        if (isOperator(id)) roles.add("OPERATOR");
        profile.put("roles", roles);
        profile.put("role", activeRole(id, (String) profile.get("role")));
        return profile;
    }

    @Transactional
    void onboard(Actor actor, AuthController.Onboarding data, String businessNumber) {
        first(db.queryForList("select id from accounts where id = ? for update", UUID.class, actor.id()));
        var profile = profile(actor.id());
        if (data.role() == Role.FOUNDER && data.founderBudget() == null && profile.get("founderBudget") == null) {
            throw invalid("예비 창업자 역할의 창업 예산을 입력하세요.");
        }
        if (data.role() == Role.OWNER && businessNumber == null && profile.get("businessStatusCheckedAt") == null) {
            throw invalid("기존 사업자 역할의 사업자등록번호를 입력하세요.");
        }
        List<?> roles = (List<?>) profile.get("roles");
        if (!roles.contains(data.role().name())) {
            if (data.role() == Role.OPERATOR) throw error(FORBIDDEN, "운영단 역할은 관리자가 부여합니다.");
            db.update("insert into account_roles(account_id, role) values (?, ?)", actor.id(), data.role().name());
        }
        db.update("""
            update accounts set active_role = ?, founder_budget = coalesce(?, founder_budget),
                business_number = coalesce(?, business_number), business_status_checked_at = coalesce(?, business_status_checked_at)
            where id = ?
            """, data.role().name(), data.founderBudget(), businessNumber, businessNumber == null ? null : now(), actor.id());
    }

    private String activeRole(UUID id, String role) {
        return "OPERATOR".equals(role) && !isOperator(id) ? null : role;
    }

    private boolean isOperator(UUID id) {
        return config.operatorAccountIds() != null && config.operatorAccountIds().contains(id);
    }

    void logout(Actor actor) {
        db.update("delete from access_tokens where token_hash = ?", actor.tokenHash());
    }

    private Session session(UUID id) {
        String token = newToken();
        OffsetDateTime expires = now().plusDays(1);
        db.update("delete from access_tokens where account_id = ? and expires_at <= current_timestamp", id);
        db.update("insert into access_tokens(token_hash, account_id, expires_at) values (?, ?, ?)", digest(token), id, expires);
        return new Session(token, expires, profile(id));
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void passwordLength(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) throw invalid("비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
    }

    private static String normalize(String address) {
        return address.strip().toLowerCase(Locale.ROOT);
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    record Session(String token, OffsetDateTime expiresAt, Map<String, Object> account) {}
    private record CredentialsRow(UUID id, String hash) {}
}

@RestController
@Tag(name = "서버·계정", description = "서버 상태 확인, 회원가입, 로그인과 계정 역할 설정.")
@RequestMapping("/api")
class AuthController {
    private final Accounts accounts;
    private final PublicData data;

    AuthController(Accounts accounts, PublicData data) {
        this.accounts = accounts;
        this.data = data;
    }

    @GetMapping("/health")
    @Operation(summary = "서버 연결 상태 확인",
        description = "서버가 요청을 받을 수 있으면 `status`가 `UP`으로 응답합니다. 로그인 없이 호출합니다.")
    Map<String, String> health() { return Map.of("status", "UP"); }

    @PostMapping("/auth/signup")
    @Operation(summary = "회원가입",
        description = "이메일, 비밀번호, 표시 이름으로 계정을 만듭니다. 아이디 로그인이 필요하면 `username`을 함께 등록합니다. 응답에는 로그인 토큰과 계정 정보가 들어 있습니다.")
    @ResponseStatus(CREATED)
    Accounts.Session signup(@Valid @RequestBody Signup input) { return accounts.signup(input); }

    @PostMapping("/auth/login")
    @Operation(summary = "로그인 토큰 발급",
        description = "등록한 `email` 또는 `username` 하나와 비밀번호로 로그인합니다. Swagger의 Authorize → BearerAuth에 응답의 `token` 값을 넣으세요.")
    Accounts.Session login(@Valid @RequestBody Credentials input) { return accounts.login(input); }

    @PostMapping("/auth/logout")
    @Operation(summary = "로그아웃",
        description = "현재 요청에 사용한 로그인 토큰을 폐기합니다. 성공하면 응답 본문 없이 204를 반환합니다.")
    @ResponseStatus(NO_CONTENT)
    void logout(@AuthenticationPrincipal Actor actor) { accounts.logout(actor); }

    @GetMapping("/me")
    @Operation(summary = "내 계정과 역할 조회",
        description = "로그인한 계정의 기본 정보와 선택한 역할을 조회합니다. 예비 창업자는 등록한 창업 예산도 확인할 수 있습니다.")
    Map<String, Object> me(@AuthenticationPrincipal Actor actor) { return accounts.profile(actor.id()); }

    @PutMapping("/me/role")
    @Operation(summary = "역할 추가 또는 활성 역할 전환",
        description = "처음 `FOUNDER`를 추가할 때 예산, `OWNER`를 추가할 때 사업자등록번호를 입력합니다. 가진 역할은 역할명만 보내 전환합니다. 응답의 `roles`는 보유 역할, `role`은 활성 역할입니다.")
    Map<String, Object> role(@AuthenticationPrincipal Actor actor, @Valid @RequestBody Onboarding input) {
        if (input.role() != Role.FOUNDER && input.founderBudget() != null) {
            throw invalid("예비 창업자 역할에 한해 창업 예산을 입력하세요.");
        }
        String number = optional(input.businessNumber());
        if (input.role() != Role.OWNER && number != null) {
            throw invalid("기존 사업자 역할에 한해 사업자등록번호를 입력하세요.");
        }
        if (number != null) {
            number = number.replace("-", "").replace(" ", "");
            if (!number.matches("[0-9]{10}")) throw invalid("사업자등록번호 10자리를 입력하세요.");
            data.requireActiveBusiness(number);
        }
        accounts.onboard(actor, input, number);
        return accounts.profile(actor.id());
    }

    private static final String USERNAME = "[a-zA-Z0-9_.-]{3,30}";
    record Credentials(@Email @Size(max = 254) String email,
        @Pattern(regexp = USERNAME) String username, @NotBlank @Size(max = 72) String password) {}
    record Signup(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 100) String displayName, @Pattern(regexp = USERNAME) String username) {}
    record Onboarding(@NotNull Role role, @PositiveOrZero Long founderBudget, @Size(max = 20) String businessNumber) {}
}
