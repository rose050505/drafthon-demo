package com.v2v.backend;

import static com.v2v.backend.Api.*;
import static org.springframework.http.HttpStatus.*;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

enum Role { FOUNDER, OWNER, STUDENT }

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
                policy.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
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

    Accounts(JdbcTemplate db) {
        this.db = db;
    }

    @Transactional
    Session signup(AuthController.Signup data) {
        passwordLength(data.password());
        UUID id = UUID.randomUUID();
        db.update("insert into accounts(id, email, password_hash, display_name) values (?, ?, ?, ?)",
            id, email(data.email()), BCRYPT.encode(data.password()), data.displayName().strip());
        return session(id);
    }

    @Transactional
    Session login(AuthController.Credentials data) {
        passwordLength(data.password());
        List<CredentialsRow> results = db.query("select id, password_hash from accounts where email = ?",
            (rs, row) -> new CredentialsRow(rs.getObject("id", UUID.class), rs.getString("password_hash")),
            email(data.email()));
        CredentialsRow account = results.isEmpty() ? null : results.getFirst();
        boolean valid = BCRYPT.matches(data.password(), account == null ? unknownPasswordHash : account.hash());
        if (!valid || account == null) throw error(UNAUTHORIZED, "이메일 또는 비밀번호를 확인하세요.");
        return session(account.id());
    }

    Actor authenticate(String token) {
        if (!token.matches("[A-Za-z0-9_-]{43}")) return null;
        String hash = digest(token);
        return db.query("""
            select a.id, a.role from access_tokens t join accounts a on a.id = t.account_id
            where t.token_hash = ? and t.expires_at > current_timestamp
            """, (rs, row) -> new Actor(rs.getObject("id", UUID.class),
                rs.getString("role") == null ? null : Role.valueOf(rs.getString("role")), hash), hash)
            .stream().findFirst().orElse(null);
    }

    Map<String, Object> profile(UUID id) {
        return first(db.queryForList("""
            select id, email, display_name as "displayName", role, founder_budget as "founderBudget",
                   business_number as "businessNumber", business_status_checked_at as "businessStatusCheckedAt"
            from accounts where id = ?
            """, id));
    }

    void onboard(Actor actor, AuthController.Onboarding data, String businessNumber) {
        int updated = db.update("""
            update accounts set role = ?, founder_budget = ?, business_number = ?, business_status_checked_at = ?
            where id = ? and role is null
            """, data.role().name(), data.founderBudget(), businessNumber, businessNumber == null ? null : now(), actor.id());
        if (updated == 0) throw conflict("이미 역할을 선택했습니다.");
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

    private static String email(String address) {
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
@RequestMapping("/api")
class AuthController {
    private final Accounts accounts;
    private final PublicData data;

    AuthController(Accounts accounts, PublicData data) {
        this.accounts = accounts;
        this.data = data;
    }

    @GetMapping("/health")
    Map<String, String> health() { return Map.of("status", "UP"); }

    @PostMapping("/auth/signup")
    @ResponseStatus(CREATED)
    Accounts.Session signup(@Valid @RequestBody Signup input) { return accounts.signup(input); }

    @PostMapping("/auth/login")
    Accounts.Session login(@Valid @RequestBody Credentials input) { return accounts.login(input); }

    @PostMapping("/auth/logout")
    @ResponseStatus(NO_CONTENT)
    void logout(@AuthenticationPrincipal Actor actor) { accounts.logout(actor); }

    @GetMapping("/me")
    Map<String, Object> me(@AuthenticationPrincipal Actor actor) { return accounts.profile(actor.id()); }

    @PutMapping("/me/role")
    Map<String, Object> role(@AuthenticationPrincipal Actor actor, @Valid @RequestBody Onboarding input) {
        if (actor.role() != null) throw conflict("이미 역할을 선택했습니다.");
        if ((input.role() == Role.FOUNDER) != (input.founderBudget() != null)) {
            throw invalid("예비 창업자 역할에 한해 창업 예산을 입력하세요.");
        }
        String number = optional(input.businessNumber());
        if ((input.role() == Role.OWNER) != (number != null)) {
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

    record Credentials(@NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 72) String password) {}
    record Signup(@NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 100) String displayName) {}
    record Onboarding(@NotNull Role role, @PositiveOrZero Long founderBudget, @Size(max = 20) String businessNumber) {}
}
