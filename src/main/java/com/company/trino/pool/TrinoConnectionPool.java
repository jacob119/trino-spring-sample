package com.company.trino.pool;

import com.company.trino.config.TrinoProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * sessionUser별 HikariCP 커넥션 풀 관리자.
 *
 * ┌─────────────────────────────────────────────────────────────┐
 * │  왜 사용자마다 별도 풀을 만드는가?                          │
 * │                                                             │
 * │  Trino의 impersonation은 JDBC URL의 sessionUser 파라미터로  │
 * │  제어된다. 이 값은 커넥션 생성 시 X-Trino-User 헤더에 담겨  │
 * │  전송되며, 커넥션이 살아있는 동안 변경할 수 없다.           │
 * │                                                             │
 * │  따라서 "alice용 커넥션"과 "bob용 커넥션"은 물리적으로       │
 * │  분리되어야 하며, 각 사용자에 대해 독립된 HikariCP 풀을     │
 * │  유지하면 커넥션을 재사용하면서도 impersonation을 보장한다. │
 * └─────────────────────────────────────────────────────────────┘
 *
 * 풀 생명주기:
 *   생성  → getDataSource() 최초 호출 시 (lazy 방식)
 *   재사용 → 같은 sessionUser 재호출 시 lastAccess 갱신
 *   제거  → LRU 초과(maxUserPools) 또는 idle 시간 초과(poolIdleThreshold)
 *   종료  → 애플리케이션 종료 시 shutdownAll()
 *
 * Trino HTTP 헤더 매핑:
 *   ldap.user / ldap.password → Authorization: Basic  (서비스 계정 인증)
 *   sessionUser               → X-Trino-User          (실제 쿼리 실행 주체)
 */
@Component
public class TrinoConnectionPool {

    private static final Logger log = LoggerFactory.getLogger(TrinoConnectionPool.class);

    private final TrinoProperties props;

    public TrinoConnectionPool(TrinoProperties props) {
        this.props = props;
    }

    // ConcurrentHashMap은 null 키를 허용하지 않으므로, 서비스 계정(sessionUser=null)에는
    // 센티넬 키를 사용한다. JDBC URL 생성 시에는 원래 null 값을 그대로 사용한다.
    private static final String SERVICE_KEY = "__service__";

    private final ConcurrentHashMap<String, PoolEntry> pools = new ConcurrentHashMap<>();

    // LRU 순서 추적용 맵. accessOrder=true로 생성하면 get/put 호출 순서대로 정렬된다.
    // maxUserPools 초과 시 removeEldestEntry()에서 가장 오래된 풀을 evict한다.
    // LinkedHashMap 자체는 스레드 안전하지 않으므로 synchronized 블록으로 보호한다.
    private final LinkedHashMap<String, Boolean> lruOrder = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            if (size() > props.getPool().getMaxUserPools()) {
                evictPool(eldest.getKey());
                return true;
            }
            return false;
        }
    };

    /**
     * sessionUser에 해당하는 DataSource를 반환한다. 풀이 없으면 새로 생성한다.
     *
     * compute()로 풀 생성을 원자적으로 처리하고, 반환값을 직접 사용해
     * 별도 get()과의 사이에서 발생할 수 있는 race condition을 제거한다.
     *
     * @param sessionUser impersonation 대상 사용자 ID. null이면 서비스 계정 풀 반환.
     */
    public DataSource getDataSource(String sessionUser) {
        String key = sessionUser != null ? sessionUser : SERVICE_KEY;

        // compute()는 원자적으로 실행되며 새 값을 직접 반환 → 별도 pools.get() 불필요
        PoolEntry entry = pools.compute(key, (k, existing) -> {
            if (existing == null || existing.isClosed()) {
                log.info("[Pool] 신규 풀 생성: sessionUser={}", sessionUser);
                return new PoolEntry(createHikariPool(sessionUser));
            }
            existing.touch();
            return existing;
        });

        // LRU 맵 갱신은 별도 synchronized 블록으로 처리한다.
        // (compute() 안에서 하면 lruOrder의 removeEldestEntry → evictPool → pools.remove가
        //  compute() 내부에서 호출되어 ConcurrentModificationException 발생 위험)
        synchronized (lruOrder) {
            lruOrder.put(key, Boolean.TRUE);
        }

        return entry.dataSource();
    }

    /**
     * 서비스 계정 전용 DataSource를 반환한다.
     * sessionUser 없이 LDAP 계정 그대로 Trino에 접속하므로, 헬스체크나 메타데이터 조회에 사용한다.
     */
    public DataSource getServiceDataSource() {
        return getDataSource(null);
    }

    // ─────────────────────────────────────────────────────────────
    // 내부 구현
    // ─────────────────────────────────────────────────────────────

    private HikariDataSource createHikariPool(String sessionUser) {
        TrinoProperties.PoolConfig poolCfg = props.getPool();

        HikariConfig config = new HikariConfig();
        config.setDriverClassName("io.trino.jdbc.TrinoDriver");

        // sessionUser가 null이면 impersonation 없는 서비스 계정 URL이 생성된다.
        // sessionUser가 있으면 URL에 &sessionUser=<encoded-user>가 추가되어 X-Trino-User 헤더로 전달된다.
        config.setJdbcUrl(props.buildJdbcUrl(sessionUser));

        // LDAP 운영 환경: ldap.user + ldap.password → Authorization: Basic 헤더로 LDAP 인증
        // INSECURE 테스트 환경: ldap.password를 비워두면 password를 설정하지 않는다.
        //   → Trino JDBC 드라이버는 password가 있으면 PASSWORD 인증을 시도하는데,
        //     PASSWORD 인증은 HTTPS가 필수이므로 HTTP 환경에서 연결이 끊긴다.
        config.setUsername(props.getLdap().getUser());
        String pw = props.getLdap().getPassword();
        if (pw != null && !pw.isBlank()) {
            config.setPassword(pw);
        }

        config.setMaximumPoolSize(poolCfg.getMaxPoolSize());
        config.setMinimumIdle(poolCfg.getMinIdle());
        config.setConnectionTimeout(poolCfg.getConnectionTimeout());
        config.setIdleTimeout(poolCfg.getIdleTimeout());
        config.setMaxLifetime(poolCfg.getMaxLifetime());

        // Trino는 SELECT 1로 커넥션 유효성을 검사한다.
        config.setConnectionTestQuery("SELECT 1");

        // 긴 사용자 이름으로 인한 풀 이름 초과 방지 (HikariCP 풀 이름 제한)
        String safeName = sessionUser != null
                ? (sessionUser.length() > 32 ? sessionUser.substring(0, 32) : sessionUser)
                : "service";
        config.setPoolName("trino-" + safeName);

        // -1로 설정하면 풀 생성 시점에 커넥션 확보를 시도하지 않는다(lazy 초기화).
        // 덕분에 Trino가 잠시 내려가도 앱 기동이 실패하지 않는다.
        config.setInitializationFailTimeout(-1);

        log.debug("[Pool] HikariCP 생성: pool={}, url={}", config.getPoolName(), config.getJdbcUrl());
        return new HikariDataSource(config);
    }

    /**
     * 지정된 sessionUser의 풀을 강제 제거한다. LRU eviction 시 호출된다.
     */
    private void evictPool(String key) {
        PoolEntry entry = pools.remove(key);
        if (entry != null && !entry.isClosed()) {
            log.info("[Pool] 풀 제거(LRU 초과): key={}", key);
            entry.close();
        }
    }

    /**
     * 오래된 유휴 풀을 주기적으로 정리한다.
     *
     * poolIdleThreshold(초) 동안 한 번도 사용되지 않은 풀을 닫는다.
     * 실행 주기는 trino.pool.pool-eviction-interval 프로퍼티에서 직접 읽는다.
     * (PoolConfig에 중복 필드를 두면 @Scheduled와 설정값이 어긋날 수 있어 제거함)
     */
    @Scheduled(fixedDelayString = "${trino.pool.pool-eviction-interval:300}000")
    public void evictIdlePools() {
        long thresholdSeconds = props.getPool().getPoolIdleThreshold();
        Instant cutoff = Instant.now().minusSeconds(thresholdSeconds);

        pools.entrySet().removeIf(e -> {
            if (e.getValue().lastAccess().isBefore(cutoff)) {
                log.info("[Pool] 유휴 풀 정리: key={}, lastAccess={}",
                        e.getKey(), e.getValue().lastAccess());
                e.getValue().close();
                synchronized (lruOrder) { lruOrder.remove(e.getKey()); }
                return true;
            }
            return false;
        });
    }

    /**
     * 애플리케이션 종료 시 모든 풀을 안전하게 닫는다.
     */
    @PreDestroy
    public void shutdownAll() {
        log.info("[Pool] 전체 풀 종료: count={}", pools.size());
        pools.values().forEach(PoolEntry::close);
        pools.clear();
    }

    // ─────────────────────────────────────────────────────────────
    // 풀 상태 조회 (모니터링 API용)
    // ─────────────────────────────────────────────────────────────

    /**
     * 현재 활성화된 모든 풀의 통계를 반환한다.
     * 풀이 닫혔거나 MXBean이 아직 초기화되지 않은 경우 해당 항목은 건너뛴다.
     */
    public Map<String, PoolStats> getStats() {
        Map<String, PoolStats> stats = new java.util.LinkedHashMap<>();
        pools.forEach((key, entry) -> {
            HikariDataSource ds = (HikariDataSource) entry.dataSource();
            if (ds.isClosed()) return;
            var mxBean = ds.getHikariPoolMXBean();
            if (mxBean == null) return;
            stats.put(key, new PoolStats(
                    mxBean.getTotalConnections(),
                    mxBean.getActiveConnections(),
                    mxBean.getIdleConnections(),
                    mxBean.getThreadsAwaitingConnection(),
                    entry.lastAccess()
            ));
        });
        return stats;
    }

    // ─────────────────────────────────────────────────────────────
    // 내부 타입
    // ─────────────────────────────────────────────────────────────

    /**
     * HikariDataSource와 마지막 접근 시간을 함께 보관하는 컨테이너.
     * AtomicBoolean.compareAndSet으로 이중 닫힘을 방지한다.
     */
    private static class PoolEntry {
        private final DataSource dataSource;
        private volatile Instant lastAccess = Instant.now();
        private final AtomicBoolean closed = new AtomicBoolean(false);

        PoolEntry(DataSource dataSource) { this.dataSource = dataSource; }

        DataSource dataSource() { return dataSource; }
        Instant lastAccess()    { return lastAccess; }
        boolean isClosed()      { return closed.get(); }

        void touch() { lastAccess = Instant.now(); }

        void close() {
            if (closed.compareAndSet(false, true)) {
                if (dataSource instanceof HikariDataSource hds) hds.close();
            }
        }
    }

    /**
     * GET /api/pool/stats 응답에 사용되는 풀 통계 스냅샷.
     */
    public record PoolStats(
            int total,         // 전체 커넥션 수
            int active,        // 현재 사용 중인 커넥션 수
            int idle,          // 대기 중인 커넥션 수
            int waiting,       // 커넥션 획득 대기 스레드 수
            Instant lastAccess // 마지막 사용 시각
    ) {}
}
