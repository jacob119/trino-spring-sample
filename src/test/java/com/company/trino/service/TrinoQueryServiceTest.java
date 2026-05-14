package com.company.trino.service;

import com.company.trino.config.TrinoProperties;
import com.company.trino.exception.ImpersonationDeniedException;
import com.company.trino.model.QueryRequest;
import com.company.trino.model.QueryResult;
import com.company.trino.pool.TrinoConnectionPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.sql.DataSource;
import java.sql.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TrinoQueryServiceTest {

    @Mock private TrinoConnectionPool connectionPool;
    @Mock private TrinoProperties props;
    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private Statement statement;
    @Mock private ResultSet resultSet;
    @Mock private ResultSetMetaData metaData;

    private TrinoQueryService service;

    @BeforeEach
    void setUp() throws SQLException {
        TrinoProperties.LdapConfig ldap = new TrinoProperties.LdapConfig();
        ldap.setUser("svc-app");

        TrinoProperties.ImpersonationConfig imp = new TrinoProperties.ImpersonationConfig();
        imp.setEnabled(true);
        imp.setAllowedPattern("[a-z0-9_]+");

        TrinoProperties.PoolConfig pool = new TrinoProperties.PoolConfig();
        pool.setQueryTimeout(60);

        when(props.getLdap()).thenReturn(ldap);
        when(props.getImpersonation()).thenReturn(imp);
        when(props.getPool()).thenReturn(pool);
        when(connectionPool.getDataSource(anyString())).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metaData);

        service = new TrinoQueryService(connectionPool, props);
    }

    @Test
    @DisplayName("유효한 sessionUser와 SQL로 쿼리 성공")
    void executeQuery_validInput_returnsResult() throws SQLException {
        when(metaData.getColumnCount()).thenReturn(2);
        when(metaData.getColumnName(1)).thenReturn("id");
        when(metaData.getColumnName(2)).thenReturn("name");
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getObject(1)).thenReturn(1L);
        when(resultSet.getObject(2)).thenReturn("Alice");

        QueryResult result = service.executeQuery("alice", new QueryRequest("SELECT id, name FROM users", 100));

        assertThat(result.sessionUser()).isEqualTo("alice");
        assertThat(result.columns()).containsExactly("id", "name");
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.elapsedMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("빈 결과셋 — 빈 rows 반환")
    void executeQuery_emptyResultSet_returnsEmptyRows() throws SQLException {
        when(metaData.getColumnCount()).thenReturn(1);
        when(metaData.getColumnName(1)).thenReturn("col");
        when(resultSet.next()).thenReturn(false);

        QueryResult result = service.executeQuery("alice", new QueryRequest("SELECT col FROM empty_table", null));

        assertThat(result.rows()).isEmpty();
        assertThat(result.rowCount()).isZero();
    }

    @Test
    @DisplayName("sessionUser null이면 예외")
    void executeQuery_nullSessionUser_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.executeQuery(null, new QueryRequest("SELECT 1", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sessionUser");
    }

    @Test
    @DisplayName("sessionUser 빈 문자열이면 예외")
    void executeQuery_blankSessionUser_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.executeQuery("  ", new QueryRequest("SELECT 1", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("허용되지 않은 sessionUser 패턴이면 ImpersonationDeniedException")
    void executeQuery_forbiddenSessionUser_throwsImpersonationDenied() {
        // allowedPattern = [a-z0-9_]+ → 대문자 포함은 거부
        assertThatThrownBy(() -> service.executeQuery("ADMIN", new QueryRequest("SELECT 1", null)))
                .isInstanceOf(ImpersonationDeniedException.class);
    }

    @Test
    @DisplayName("SQLException 발생 시 TrinoQueryException으로 래핑")
    void executeQuery_sqlException_wrappedAsTrinoQueryException() throws SQLException {
        when(statement.executeQuery(anyString()))
                .thenThrow(new SQLException("Connection refused"));

        assertThatThrownBy(() -> service.executeQuery("alice", new QueryRequest("SELECT 1", null)))
                .isInstanceOf(TrinoQueryService.TrinoQueryException.class)
                .hasMessageContaining("query execution failed");
    }

    @Test
    @DisplayName("impersonation disabled이면 sessionUser 검증 건너뜀")
    void executeQuery_impersonationDisabled_skipValidation() throws SQLException {
        TrinoProperties.ImpersonationConfig imp = new TrinoProperties.ImpersonationConfig();
        imp.setEnabled(false);
        when(props.getImpersonation()).thenReturn(imp);

        when(metaData.getColumnCount()).thenReturn(1);
        when(metaData.getColumnName(1)).thenReturn("col");
        when(resultSet.next()).thenReturn(false);

        // ADMIN은 allowedPattern에 걸리지만 impersonation disabled이므로 통과
        assertThatCode(() -> service.executeQuery("ADMIN", new QueryRequest("SELECT col FROM t", null)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("maxRows null이면 기본값 1000 적용")
    void executeQuery_nullMaxRows_usesDefault1000() throws SQLException {
        when(metaData.getColumnCount()).thenReturn(0);
        when(resultSet.next()).thenReturn(false);

        service.executeQuery("alice", new QueryRequest("SELECT 1", null));

        verify(statement).setMaxRows(1000);
    }
}
