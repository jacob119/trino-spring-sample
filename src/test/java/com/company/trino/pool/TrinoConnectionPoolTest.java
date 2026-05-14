package com.company.trino.pool;

import com.company.trino.config.TrinoProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.sql.DataSource;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrinoConnectionPoolTest {

    @Mock private TrinoProperties props;

    private TrinoConnectionPool pool;

    @BeforeEach
    void setUp() {
        TrinoProperties.LdapConfig ldap = new TrinoProperties.LdapConfig();
        ldap.setUser("svc-app");
        ldap.setPassword("svc-password");

        TrinoProperties.PoolConfig poolCfg = new TrinoProperties.PoolConfig();
        poolCfg.setMaxPoolSize(2);
        poolCfg.setMinIdle(0);
        poolCfg.setConnectionTimeout(3000);
        poolCfg.setIdleTimeout(60000);
        poolCfg.setMaxLifetime(120000);
        poolCfg.setMaxUserPools(3);
        poolCfg.setPoolIdleThreshold(600);

        when(props.getLdap()).thenReturn(ldap);
        when(props.getPool()).thenReturn(poolCfg);
        when(props.buildJdbcUrl("alice")).thenReturn(
                "jdbc:trino://localhost:8081/tpch/tiny?SSL=false&sessionUser=alice");
        when(props.buildJdbcUrl("bob")).thenReturn(
                "jdbc:trino://localhost:8081/tpch/tiny?SSL=false&sessionUser=bob");
        when(props.buildJdbcUrl(null)).thenReturn(
                "jdbc:trino://localhost:8081/tpch/tiny?SSL=false");

        pool = new TrinoConnectionPool(props);
    }

    @Test
    @DisplayName("같은 sessionUser 호출 시 동일 DataSource 반환")
    void getDataSource_sameUser_returnsSameInstance() {
        DataSource ds1 = pool.getDataSource("alice");
        DataSource ds2 = pool.getDataSource("alice");

        assertThat(ds1).isSameAs(ds2);
    }

    @Test
    @DisplayName("다른 sessionUser 호출 시 다른 DataSource 반환")
    void getDataSource_differentUsers_returnsDifferentInstances() {
        DataSource dsAlice = pool.getDataSource("alice");
        DataSource dsBob = pool.getDataSource("bob");

        assertThat(dsAlice).isNotSameAs(dsBob);
    }

    @Test
    @DisplayName("getServiceDataSource는 sessionUser=null DataSource 반환")
    void getServiceDataSource_returnsNullUserPool() {
        DataSource serviceDs = pool.getServiceDataSource();

        assertThat(serviceDs).isNotNull();
        assertThat(pool.getServiceDataSource()).isSameAs(serviceDs);
    }

    @Test
    @DisplayName("풀 통계에 생성된 사용자 포함")
    void getStats_containsCreatedUsers() {
        pool.getDataSource("alice");
        pool.getDataSource("bob");

        var stats = pool.getStats();

        assertThat(stats).containsKey("alice");
        assertThat(stats).containsKey("bob");
    }

    @Test
    @DisplayName("shutdownAll 후 풀 통계 비어 있음")
    void shutdownAll_clearsAllPools() {
        pool.getDataSource("alice");
        pool.shutdownAll();

        assertThat(pool.getStats()).isEmpty();
    }

    @Test
    @DisplayName("동시 요청 시 사용자별 풀이 중복 생성되지 않음")
    void getDataSource_concurrent_noDuplicatePools() throws InterruptedException {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        Set<DataSource> dataSources = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    dataSources.add(pool.getDataSource("alice"));
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(dataSources).hasSize(1);
    }
}
