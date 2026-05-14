package com.company.trino.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * application.yml의 trino.* 설정을 바인딩하는 프로퍼티 클래스.
 *
 * @Component 없이 @EnableConfigurationProperties(TrinoProperties.class)로 등록한다.
 * @Validated를 통해 애플리케이션 기동 시 설정값을 검증한다.
 *
 * 주요 설정 그룹:
 *   - 기본 연결 정보: host, port, catalog, schema, ssl
 *   - ldap: Trino 인증에 사용할 서비스 계정 (Authorization: Basic)
 *   - impersonation: 애플리케이션 레벨 sessionUser 허용 정책
 *   - pool: HikariCP 풀 크기 및 생명주기 설정
 */
@Validated
@ConfigurationProperties(prefix = "trino")
public class TrinoProperties {

    @NotBlank
    private String host = "localhost";

    @Min(1) @Max(65535)
    private int port = 8080;

    private String catalog;
    private String schema;
    private boolean ssl = false;

    @Valid
    private LdapConfig ldap = new LdapConfig();
    private ImpersonationConfig impersonation = new ImpersonationConfig();
    private PoolConfig pool = new PoolConfig();

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getCatalog() { return catalog; }
    public void setCatalog(String catalog) { this.catalog = catalog; }
    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }
    public boolean isSsl() { return ssl; }
    public void setSsl(boolean ssl) { this.ssl = ssl; }
    public LdapConfig getLdap() { return ldap; }
    public void setLdap(LdapConfig ldap) { this.ldap = ldap; }
    public ImpersonationConfig getImpersonation() { return impersonation; }
    public void setImpersonation(ImpersonationConfig impersonation) { this.impersonation = impersonation; }
    public PoolConfig getPool() { return pool; }
    public void setPool(PoolConfig pool) { this.pool = pool; }

    /**
     * Trino 인증 서비스 계정 설정.
     *
     * LDAP 운영 환경: user = LDAP 서비스 계정, password = LDAP 비밀번호.
     * INSECURE 테스트 환경: user = 임의 사용자명, password = 비워둠.
     *   → password가 있으면 JDBC 드라이버가 PASSWORD 인증을 시도해 HTTP 환경에서 실패한다.
     */
    public static class LdapConfig {
        @NotBlank
        private String user;
        private String password;

        public String getUser() { return user; }
        public void setUser(String user) { this.user = user; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
    }

    /**
     * 애플리케이션 레벨 impersonation 허용 정책.
     *
     * allowedPattern은 사전 필터 역할만 하며, 최종 권한은 Trino rules.json이 결정한다.
     * 기본 패턴은 일반적인 AD 계정 형식(영문자로 시작, 영숫자·점·하이픈·밑줄 허용)이다.
     */
    public static class ImpersonationConfig {
        private boolean enabled = true;
        /** 허용할 sessionUser 정규식. 운영 환경에서는 도메인 규칙에 맞게 좁혀서 사용한다. */
        private String allowedPattern = "^[a-zA-Z][a-zA-Z0-9._-]{0,63}$";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getAllowedPattern() { return allowedPattern; }
        public void setAllowedPattern(String allowedPattern) { this.allowedPattern = allowedPattern; }
    }

    /**
     * 사용자별 HikariCP 풀 설정.
     *
     * maxUserPools 초과 시 LRU 방식으로 오래된 풀이 제거된다.
     * poolIdleThreshold: 이 시간(초) 동안 미사용된 풀은 스케줄러가 자동 제거한다.
     * queryTimeout: Statement 최대 실행 시간(초). 0이면 무제한.
     *
     * pool-eviction-interval은 @Scheduled 어노테이션이 직접 프로퍼티를 참조하므로
     * PoolConfig에 별도 필드를 두지 않는다.
     */
    public static class PoolConfig {
        private int maxPoolSize = 10;
        private int minIdle = 1;
        private long connectionTimeout = 30000;
        private long idleTimeout = 300000;
        private long maxLifetime = 1800000;
        private int maxUserPools = 50;
        private long poolIdleThreshold = 600;
        private int queryTimeout = 300;

        public int getMaxPoolSize() { return maxPoolSize; }
        public void setMaxPoolSize(int maxPoolSize) { this.maxPoolSize = maxPoolSize; }
        public int getMinIdle() { return minIdle; }
        public void setMinIdle(int minIdle) { this.minIdle = minIdle; }
        public long getConnectionTimeout() { return connectionTimeout; }
        public void setConnectionTimeout(long connectionTimeout) { this.connectionTimeout = connectionTimeout; }
        public long getIdleTimeout() { return idleTimeout; }
        public void setIdleTimeout(long idleTimeout) { this.idleTimeout = idleTimeout; }
        public long getMaxLifetime() { return maxLifetime; }
        public void setMaxLifetime(long maxLifetime) { this.maxLifetime = maxLifetime; }
        public int getMaxUserPools() { return maxUserPools; }
        public void setMaxUserPools(int maxUserPools) { this.maxUserPools = maxUserPools; }
        public long getPoolIdleThreshold() { return poolIdleThreshold; }
        public void setPoolIdleThreshold(long poolIdleThreshold) { this.poolIdleThreshold = poolIdleThreshold; }
        public int getQueryTimeout() { return queryTimeout; }
        public void setQueryTimeout(int queryTimeout) { this.queryTimeout = queryTimeout; }
    }

    /**
     * sessionUser를 포함한 Trino JDBC URL을 생성한다.
     *
     * sessionUser는 URL 인코딩하여 URL 인젝션을 방지한다.
     * sessionUser 파라미터는 Trino JDBC 드라이버가 X-Trino-User 헤더에 담아 전송한다.
     *
     * 생성 예시:
     *   jdbc:trino://localhost:8081/tpch/tiny?SSL=false&sessionUser=alice
     *
     * @param sessionUser null이면 sessionUser 파라미터를 URL에 포함하지 않는다.
     */
    public String buildJdbcUrl(String sessionUser) {
        StringBuilder url = new StringBuilder("jdbc:trino://")
                .append(host).append(":").append(port);

        if (catalog != null) url.append("/").append(catalog);
        if (schema != null) url.append("/").append(schema);

        url.append("?SSL=").append(ssl);

        if (sessionUser != null && !sessionUser.isEmpty()) {
            url.append("&sessionUser=")
               .append(URLEncoder.encode(sessionUser, StandardCharsets.UTF_8));
        }

        return url.toString();
    }
}
