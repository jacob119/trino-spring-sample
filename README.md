# Trino 478 Spring Boot 샘플

Trino 478 JDBC를 이용하여 **사용자 impersonation**과 **사용자별 커넥션 풀 관리**를 구현한 Spring Boot 샘플 프로젝트입니다.

Windows AD 환경에서 **단일 서비스 계정**으로 Trino에 인증하면서, 실제 쿼리는 **요청한 사용자 권한**으로 실행하는 패턴을 제공합니다.

---

## 목차

1. [아키텍처](#아키텍처)
2. [사전 요구사항](#사전-요구사항)
3. [빠른 시작](#빠른-시작)
4. [설정 레퍼런스](#설정-레퍼런스)
5. [API 레퍼런스](#api-레퍼런스)
6. [Impersonation 동작 원리](#impersonation-동작-원리)
7. [커넥션 풀 관리 전략](#커넥션-풀-관리-전략)
8. [로컬 테스트 vs 운영 환경](#로컬-테스트-vs-운영-환경)
9. [Trino 서버 설정](#trino-서버-설정)
10. [트러블슈팅](#트러블슈팅)

---

## 아키텍처

```
클라이언트 (C# / DBeaver / curl)
        │
        │  POST /api/query
        │  Header: X-Session-User: alice        ← 실제 쿼리 실행 주체
        │  Body:   { "sql": "SELECT ..." }
        ▼
┌─────────────────────────────┐
│     Spring Boot App          │
│                              │
│  QueryController             │
│       │                      │
│  TrinoQueryService           │
│       │  validateImpersonation()
│       │                      │
│  TrinoConnectionPool         │
│       │  alice용 풀 조회/생성  │
│       │  JDBC URL:            │
│       │  ...?sessionUser=alice│
└───────┼──────────────────────┘
        │
        │  Authorization: Basic svc-app:pass   ← 서비스 계정 인증
        │  X-Trino-User: alice                 ← impersonation 대상
        ▼
┌─────────────────────────────┐
│         Trino 478            │
│                              │
│  1. svc-app LDAP 인증        │
│  2. rules.json 확인          │
│     svc-app → alice 허용?    │
│  3. alice 권한으로 쿼리 실행  │
└─────────────────────────────┘
```

### 핵심 컴포넌트

| 클래스 | 역할 |
|--------|------|
| `TrinoConnectionPool` | sessionUser별 HikariCP 풀 생성/관리/제거 |
| `TrinoQueryService` | impersonation 검증 후 쿼리 실행 |
| `QueryController` | REST API 엔드포인트 |
| `TrinoProperties` | application.yml 설정 바인딩 |

---

## 사전 요구사항

| 항목 | 버전 |
|------|------|
| Java | 17 이상 (Java 24 동작 확인) |
| Maven | 3.8 이상 |
| Trino | 478 (다른 버전도 동작 가능) |

---

## 빠른 시작

### 1. 저장소 클론

```bash
git clone <repository-url>
cd trino-spring-sample
```

### 2. Trino 로컬 실행 (Docker)

```bash
# Trino 478 컨테이너 시작 (HTTP 모드)
docker run -d \
  --name trino-478 \
  -p 8081:8080 \
  trinodb/trino:478
```

LDAP 인증 환경이라면 [Trino 서버 설정](#trino-서버-설정)을 먼저 완료하세요.

### 3. 설정 확인

`src/main/resources/application.yml`에서 Trino 연결 정보를 확인합니다.

```yaml
trino:
  host: localhost
  port: 8081
  ldap:
    user: admin      # Trino에 인증할 서비스 계정
    password:        # INSECURE 모드에서는 비워둠
```

### 4. 빌드 및 실행

```bash
# 테스트 실행
mvn test

# 애플리케이션 시작
mvn spring-boot:run
```

앱이 `http://localhost:8083`에서 시작됩니다.

### 5. 동작 확인

```bash
# impersonation 확인
curl -H "X-Session-User: alice" http://localhost:8083/api/whoami
# 응답: {"sessionUser":"alice","trinoCurrentUser":"alice"}

# 쿼리 실행
curl -X POST http://localhost:8083/api/query \
  -H "X-Session-User: alice" \
  -H "Content-Type: application/json" \
  -d '{"sql":"SELECT * FROM tpch.tiny.customer LIMIT 3"}'
```

---

## 설정 레퍼런스

`application.yml` 전체 설정과 설명입니다.

### Trino 연결 기본 설정

```yaml
trino:
  host: localhost        # Trino 서버 주소
  port: 8081             # Trino HTTP/HTTPS 포트
  catalog: tpch          # 기본 카탈로그 (쿼리에서 직접 지정도 가능)
  schema: tiny           # 기본 스키마
  ssl: false             # HTTPS 사용 여부 (운영: true)
```

### LDAP 서비스 계정

```yaml
trino:
  ldap:
    user: svc-app                          # Trino에 인증할 서비스 계정
    password: ${TRINO_SVC_PASSWORD:}       # 운영: 환경변수로 주입, 개발: 비워둠
```

> **중요**: `password`를 설정하면 Trino JDBC 드라이버가 PASSWORD 인증을 시도합니다.
> PASSWORD 인증은 HTTPS가 필수이므로, HTTP(INSECURE) 환경에서는 `password`를 비워두세요.

### Impersonation 정책

```yaml
trino:
  impersonation:
    enabled: true
    # 기본값: 영문자로 시작하는 일반적인 AD 계정 형식
    allowed-pattern: "^[a-zA-Z][a-zA-Z0-9._-]{0,63}$"
```

`allowed-pattern` 예시:

| 패턴 | 설명 |
|------|------|
| `"^[a-zA-Z][a-zA-Z0-9._-]{0,63}$"` | 기본값: 영문자로 시작, 영숫자/점/하이픈/밑줄, 최대 64자 |
| `"^[a-z][a-z0-9._-]{2,19}$"` | 소문자로 시작하는 3~20자 계정만 허용 |
| `"^(dev\|ops\|data)-.*"` | 특정 접두사 계정만 허용 |
| `".*"` | 모든 사용자 허용 (개발 전용) |

### 커넥션 풀 설정

```yaml
trino:
  pool:
    max-pool-size: 10           # 사용자당 최대 커넥션 수
    min-idle: 1                 # 사용자당 최소 유지 커넥션 수
    connection-timeout: 30000   # 커넥션 획득 대기 최대 시간 (ms)
    idle-timeout: 300000        # 유휴 커넥션 반납 시간 (ms)
    max-lifetime: 1800000       # 커넥션 최대 수명 (ms)
    max-user-pools: 50          # 동시 유지 최대 사용자 풀 수
    pool-eviction-interval: 300 # 유휴 풀 정리 주기 (초)
    pool-idle-threshold: 600    # 미사용 시 풀 제거 기준 시간 (초)
```

---

## API 레퍼런스

### POST /api/query — 쿼리 실행

**요청**

```
POST /api/query
Header: X-Session-User: {사용자 ID}
Header: Content-Type: application/json
Body: { "sql": "SELECT ...", "maxRows": 1000 }
```

| 필드 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `sql` | string | 필수 | 실행할 SQL |
| `maxRows` | integer | 1000 | 최대 반환 행 수 |

**응답**

```json
{
  "sessionUser": "alice",
  "columns": ["custkey", "name", "nationkey"],
  "rows": [
    {"custkey": 1, "name": "Customer#000000001", "nationkey": 15}
  ],
  "rowCount": 1,
  "elapsedMs": 342
}
```

**curl 예시**

```bash
curl -X POST http://localhost:8083/api/query \
  -H "X-Session-User: alice" \
  -H "Content-Type: application/json" \
  -d '{
    "sql": "SELECT custkey, name FROM tpch.tiny.customer LIMIT 5",
    "maxRows": 10
  }'
```

---

### GET /api/whoami — 현재 사용자 확인

impersonation이 올바르게 동작하는지 확인합니다. `sessionUser`와 `trinoCurrentUser`가 일치하면 정상입니다.

**요청**

```
GET /api/whoami
Header: X-Session-User: {사용자 ID}
```

**응답**

```json
{
  "sessionUser": "alice",
  "trinoCurrentUser": "alice"
}
```

**curl 예시**

```bash
curl -H "X-Session-User: alice" http://localhost:8083/api/whoami
```

---

### GET /api/pool/stats — 커넥션 풀 통계

현재 활성화된 사용자별 커넥션 풀 현황을 조회합니다.

**응답**

```json
{
  "alice": {
    "total": 2,
    "active": 1,
    "idle": 1,
    "waiting": 0,
    "lastAccess": "2026-05-14T13:19:12.753503Z"
  },
  "bob": {
    "total": 1,
    "active": 0,
    "idle": 1,
    "waiting": 0,
    "lastAccess": "2026-05-14T13:18:05.001234Z"
  }
}
```

| 필드 | 설명 |
|------|------|
| `total` | 전체 커넥션 수 |
| `active` | 현재 쿼리 실행 중인 커넥션 수 |
| `idle` | 대기 중인 커넥션 수 |
| `waiting` | 커넥션을 기다리는 스레드 수 |
| `lastAccess` | 마지막 쿼리 실행 시각 (UTC) |

---

### 에러 응답

모든 오류는 다음 형식으로 반환됩니다.

```json
{ "error": "오류 메시지" }
```

| 상황 | HTTP 상태 | 메시지 |
|------|-----------|--------|
| X-Session-User 헤더 누락 | 400 | `"required header missing: X-Session-User"` |
| sessionUser가 빈 값 | 400 | `"sessionUser는 필수입니다."` |
| sql 필드 누락 | 400 | `"sql: sql is required"` |
| allowedPattern 불일치 | 403 | `"impersonation denied"` |
| Trino 연결/쿼리 실패 | 503 | `"query execution failed"` |

> 내부 SQL 오류 메시지는 클라이언트에 노출하지 않고 서버 로그에만 기록됩니다.

---

## Impersonation 동작 원리

### Trino의 두 가지 사용자 개념

| 개념 | HTTP 헤더 | 설정 위치 | 역할 |
|------|-----------|-----------|------|
| **인증 사용자** | `Authorization: Basic` | `ldap.user / password` | Trino LDAP 인증 주체 |
| **세션 사용자** | `X-Trino-User` | `sessionUser` (JDBC URL) | 실제 쿼리 실행 주체 |

두 사용자가 다를 때 Trino가 impersonation을 수행합니다.

### JDBC URL과 헤더 매핑

```
jdbc:trino://localhost:8081/tpch/tiny?SSL=false&sessionUser=alice
                                                └─────────────────→ X-Trino-User: alice

HikariConfig.setUsername("svc-app") ──────────────────────────────→ Authorization: Basic svc-app:
```

### Trino rules.json 설정 (impersonation 허용)

서비스 계정(`svc-app`)이 모든 사용자로 impersonation할 수 있도록 설정합니다.

```json
{
  "impersonation": [
    {
      "original_user": "svc-app",
      "new_user": ".*",
      "allow": true
    }
  ]
}
```

---

## 커넥션 풀 관리 전략

### 왜 사용자별 풀이 필요한가?

Trino의 `sessionUser`는 JDBC URL에 포함되어 **커넥션 생성 시 고정**됩니다. 이미 생성된 커넥션의 `sessionUser`를 변경할 수 없으므로, alice용 커넥션을 bob이 재사용할 수 없습니다.

```
alice 요청 → alice용 풀에서 커넥션 획득 → X-Trino-User: alice
bob 요청   → bob용 풀에서 커넥션 획득   → X-Trino-User: bob
```

### 풀 생명주기

```
첫 요청 → 풀 생성 (lazy)
재요청  → 기존 풀 재사용 (lastAccess 갱신)
풀 초과 → LRU 방식으로 가장 오래된 풀 제거 (maxUserPools 기준)
미사용  → 스케줄러가 주기적으로 제거 (poolIdleThreshold 기준)
종료    → 전체 풀 정리 (@PreDestroy)
```

### 메모리 예측

```
사용 메모리 ≈ max-user-pools × max-pool-size × (커넥션당 약 500KB)
예) 50명 × 10커넥션 × 500KB ≈ 약 250MB (JVM 힙 기준)
```

> Trino JDBC 커넥션은 쿼리 상태 및 통신 버퍼를 포함하므로 일반 DB 커넥션보다 메모리를 더 사용합니다.
> 실제 사용량은 `jmap -histo` 또는 JVM 힙 모니터링으로 측정하세요.

---

## 로컬 테스트 vs 운영 환경

### 로컬 테스트 (INSECURE 모드)

```yaml
# application.yml
trino:
  host: localhost
  port: 8081
  ssl: false
  ldap:
    user: admin
    password:          # 비워둠 — INSECURE 모드

# Trino config.properties
http-server.authentication.type=INSECURE
internal-communication.shared-secret=<랜덤값>
```

특징:
- Trino가 X-Trino-User를 그대로 신뢰
- rules.json impersonation 규칙이 적용되지 않음
- LDAP 서버 불필요

### 운영 환경 (LDAP + HTTPS)

```yaml
# application.yml
trino:
  host: trino.company.internal
  port: 443
  ssl: true
  ldap:
    user: svc-app
    password: ${TRINO_SVC_PASSWORD}    # 환경 변수로 주입
```

```properties
# Trino config.properties
http-server.authentication.type=PASSWORD
http-server.https.enabled=true
http-server.https.port=443
http-server.https.keystore.path=/etc/trino/keystore.jks
http-server.https.keystore.key=<keystore-password>
internal-communication.shared-secret=<랜덤값>
```

```properties
# Trino password-authenticator.properties
password-authenticator.name=ldap
ldap.url=ldaps://ad.company.internal:636
ldap.bind-dn=CN=svc-ldap,OU=Service,DC=company,DC=com
ldap.bind-password=<ldap-bind-password>
ldap.user-bind-pattern=sAMAccountName=${USER}@company.com
```

특징:
- LDAP으로 서비스 계정 인증
- rules.json impersonation 규칙 적용
- 암호화 통신 (HTTPS)

---

## Trino 서버 설정

### access-control.properties

```properties
access-control.name=file
security.config-file=/etc/trino/rules.json
```

### rules.json — impersonation 허용 예시

```json
{
  "impersonation": [
    {
      "original_user": "svc-app",
      "new_user": ".*",
      "allow": true
    },
    {
      "original_user": ".*",
      "new_user": ".*",
      "allow": false
    }
  ],
  "catalogs": [
    {
      "user": ".*",
      "catalog": ".*",
      "allow": "all"
    }
  ]
}
```

---

## 트러블슈팅

### 연결 타임아웃 (30초 후 실패)

**증상**: `Connection is not available, request timed out after 30004ms`

**원인 및 해결**:

| 원인 | 해결 방법 |
|------|-----------|
| HTTP 환경에서 `ldap.password`가 설정됨 | `password` 항목을 비워두세요 |
| Trino 서버가 실행 중이 아님 | `curl http://trino-host:port/v1/info` 로 확인 |
| 방화벽으로 포트 차단 | 네트워크 경로 및 포트 확인 |

### impersonation이 동작하지 않음

**증상**: `whoami` 응답에서 `trinoCurrentUser`가 `sessionUser`와 다름

**원인 및 해결**:

| 원인 | 해결 방법 |
|------|-----------|
| INSECURE 모드 — 규칙 적용 안 됨 | 운영 환경에서는 LDAP + HTTPS 구성 필요 |
| rules.json에 허용 규칙 없음 | `original_user: svc-app, new_user: .*` 규칙 추가 |
| access-control.properties 미설정 | Trino 서버에 파일 접근 제어 설정 확인 |

### `Password not allowed for insecure authentication` 오류

**원인**: Trino PASSWORD 인증이 설정되어 있는데 HTTP로 접속하는 경우

**해결**: SSL을 활성화하거나, INSECURE 모드로 전환하세요.

### HikariCP 풀이 너무 많이 생성됨

**증상**: 풀 통계에서 사용자 수가 예상보다 많음

**해결**: `max-user-pools`를 줄이거나 `pool-idle-threshold`를 짧게 설정하세요.

```yaml
pool:
  max-user-pools: 20
  pool-idle-threshold: 300  # 5분 미사용 시 제거
```

### `IllegalArgumentException: sessionUser는 필수입니다.`

**원인**: `X-Session-User` 헤더가 비어있거나 공백만 있는 경우

**해결**: 헤더에 유효한 사용자 ID를 전달하세요.

```bash
curl -H "X-Session-User: alice" ...
```

---

## 프로젝트 구조

```
src/
├── main/java/com/company/trino/
│   ├── TrinoApplication.java            # 앱 진입점 (@EnableScheduling, TaskScheduler)
│   ├── config/
│   │   └── TrinoProperties.java         # application.yml 바인딩 (@Validated)
│   ├── controller/
│   │   └── QueryController.java         # REST API 엔드포인트
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java  # 전역 예외 핸들러 (@RestControllerAdvice)
│   │   └── ImpersonationDeniedException.java  # impersonation 거부 예외
│   ├── model/
│   │   ├── QueryRequest.java            # 쿼리 요청 (record + @Valid)
│   │   └── QueryResult.java             # 쿼리 결과 (record)
│   ├── pool/
│   │   └── TrinoConnectionPool.java     # 사용자별 HikariCP 풀 관리
│   └── service/
│       └── TrinoQueryService.java       # 쿼리 실행 및 impersonation 검증
└── test/java/com/company/trino/
    ├── pool/
    │   └── TrinoConnectionPoolTest.java # 풀 생성/재사용/종료/동시성 단위 테스트
    └── service/
        └── TrinoQueryServiceTest.java   # 쿼리 실행/검증 단위 테스트
```

## 의존성

| 라이브러리 | 용도 |
|-----------|------|
| `spring-boot-starter-web` | REST API 서버 |
| `spring-boot-starter-jdbc` | HikariCP 포함 |
| `spring-boot-starter-validation` | Bean Validation (@NotBlank, @Valid 등) |
| `io.trino:trino-jdbc:478` | Trino JDBC 드라이버 |
| `spring-boot-configuration-processor` | application.yml 자동 완성 |
