# V2V 백엔드

대구 동성로의 확인된 빈 점포, 예비·기존 창업자의 과제, 대학생 지원과 매칭을 관리합니다. Java 21, Spring Boot, Flyway를 사용합니다. 로컬 DB는 파일 기반 H2이며 배포에서는 PostgreSQL을 지정할 수 있습니다. 초기 데이터는 비어 있습니다.

## 발급할 API 키

| 서비스 | 환경 변수 | 사용 위치 |
| --- | --- | --- |
| [국세청 사업자등록정보 상태조회](https://www.data.go.kr/data/15081808/openapi.do) | `V2V_NTS_API_KEY` | 기존 사업자 역할 등록 시 계속사업자 상태 확인 |
| [소상공인시장진흥공단 상가(상권)정보](https://www.data.go.kr/data/15012005/openapi.do) | `V2V_SEMAS_API_KEY` | 지도 영역 내 주변 가게의 업종과 좌표, 창업 입지 분석의 경쟁 현황 |
| [국토교통부 건축HUB 건축물대장](https://www.data.go.kr/data/15134735/openapi.do) | `V2V_BUILDING_HUB_API_KEY` | 후보 건물 표제부의 용도, 면적 등 조회 |
| [카카오 로컬 REST API](https://developers.kakao.com/docs/ko/local/dev-guide#address-coord) | `V2V_KAKAO_REST_KEY` | DB에 등록된 건물 대신 도로명 주소를 직접 입력했을 때 좌표 조회 |
| [SGIS 데이터 API](https://sgis.mods.go.kr/developer/html/newOpenApi/app/apply.html) | `V2V_SGIS_SERVICE_ID`, `V2V_SGIS_SECRET_KEY` | 선택 위치의 행정동 인구·종사자·생활업종 사업체 수 |

공공데이터포털에서 로그인하고 각 데이터 상세 페이지에서 활용신청을 합니다. 각 서비스 호출이 허용된 계정의 일반 인증키 중 **Decoding** 값을 환경 변수에 넣습니다. 서버가 요청에 맞게 인코딩합니다. 프론트에서 카카오 지도 SDK를 쓰는 경우 [카카오 지도 JavaScript 키와 도메인 등록](https://apis.map.kakao.com/web/guide/)도 필요합니다.

`V2V_AI_WORKER_TOKEN`과 `V2V_CATALOG_ADMIN_TOKEN`에는 팀이 생성한 서로 다른 무작위 토큰을 넣습니다. AI 팀에 공유할 값은 AI 워커 토큰입니다. 실제 모델 API 키는 AI 팀의 실행 환경에 둡니다. 운영자와 워커 토큰을 브라우저 코드에 넣으면 안 됩니다.

## 시작

PowerShell에서 JDK 21로 실행합니다. IntelliJ 실행 구성의 환경 변수에 같은 이름을 사용할 수 있습니다.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:V2V_NTS_API_KEY = '<국세청 Decoding 키>'
$env:V2V_SEMAS_API_KEY = '<소진공 Decoding 키>'
$env:V2V_BUILDING_HUB_API_KEY = '<건축HUB Decoding 키>'
$env:V2V_KAKAO_REST_KEY = '<카카오 REST API 키>'
$env:V2V_SGIS_SERVICE_ID = Read-Host 'SGIS 서비스 ID'
$env:V2V_SGIS_SECRET_KEY = [System.Net.NetworkCredential]::new('', (Read-Host 'SGIS 보안 Key' -AsSecureString)).Password
$env:V2V_AI_WORKER_TOKEN = '<AI 팀과 공유한 토큰>'
$env:V2V_CATALOG_ADMIN_TOKEN = '<공실 등록 담당자 토큰>'
.\gradlew.bat bootRun
.\gradlew.bat test
```

기본 주소는 `http://127.0.0.1:8080`입니다. `GET /api/health`로 확인합니다. `PORT`, `V2V_BIND_ADDRESS`, `V2V_CORS_ORIGINS`를 지정할 수 있습니다. 배포에서는 `V2V_DB_URL=jdbc:postgresql://호스트:5432/데이터베이스`, `V2V_DB_USER`, `V2V_DB_PASSWORD`를 설정합니다. 공공 API 키 미설정은 `503`, 제공기관 오류는 `502`로 처리합니다.

## 공용 서버 배포

저장소의 `backend/`를 Docker 빌드 컨텍스트로 사용합니다. Render에서는 Docker 웹 서비스의 Root Directory를 `backend`로, Health Check Path를 `/api/health`로 지정합니다. 이미지는 `cloud` 프로필로 시작하고 호스팅 제공자가 지정한 `PORT`를 사용합니다.

| 환경 변수 | 배포 서버에 넣을 값 |
| --- | --- |
| `V2V_DB_URL` | PostgreSQL JDBC URL. Supabase의 IPv4 Session pooler를 쓰면 `jdbc:postgresql://실제-풀러-호스트:5432/postgres?sslmode=require` 형식 |
| `V2V_DB_USER` | DB 연결 화면에 표시된 사용자 이름. Supabase Session pooler 이름은 일반적으로 `postgres.<프로젝트-ref>` |
| `V2V_DB_PASSWORD` | DB 비밀번호 |
| `V2V_CORS_ORIGINS` | 실제 프론트 배포 주소. 여러 출처는 쉼표로 구분 |
| 위 표의 외부 API 키와 V2V 연동 토큰 | 필요한 공개 데이터 API 키, AI 워커 토큰과 운영자 토큰을 배포 서비스의 환경 변수로 입력 |

최초 시작 시 Flyway가 PostgreSQL의 전용 `v2v` 스키마에 테이블을 생성합니다. Supabase에서는 `v2v` 스키마를 Data API 공개 목록에 추가하지 않습니다. 기존 H2 데이터를 옮기는 작업은 별도입니다. 배포된 서버의 `/api/health`가 `UP`이면 같은 주소의 `/swagger-ui.html`을 팀에 공유하고 프론트와 AI 워커에 서버의 기본 URL을 설정합니다.

## 프론트 API

서버를 실행한 뒤 `http://127.0.0.1:8080/swagger-ui.html`에서 각 API 이름과 설명을 펼쳐 보고 **Try it out**으로 요청을 보낼 수 있습니다. `127.0.0.1`은 브라우저가 열린 PC 자신을 가리킵니다. 같은 서버와 데이터를 사용할 팀원에게는 백엔드를 배포한 주소에 `/swagger-ui.html`을 붙여 공유합니다. OpenAPI JSON은 같은 서버의 `/v3/api-docs`에서 받을 수 있습니다.

1. `GET /api/health`를 실행해 연결 상태를 확인합니다.
2. `POST /api/auth/login`을 실행한 뒤, **Authorize → BearerAuth**에 응답의 `token` 값만 넣습니다. 역할별 API는 계정의 역할 설정이 필요합니다.
3. AI 워커와 공실 등록 API에는 각 담당자에게 발급된 연동 토큰을 **Authorize**의 해당 헤더 항목에 입력합니다. 로그인 토큰과 두 연동 토큰은 Swagger 화면에서 각자 입력합니다.

일반 사용자는 `Authorization: Bearer <token>`으로 인증합니다. 토큰은 회원가입 또는 로그인 응답의 `token`이고 유효기간은 24시간입니다. JSON 요청의 Content-Type은 `application/json`입니다.

| 흐름 | 메서드 및 경로 | 주요 입력과 동작 |
| --- | --- | --- |
| 회원 | `POST /api/auth/signup`, `POST /api/auth/login` | `email`, `password`. 회원가입에는 `displayName` 추가 |
| 계정과 역할 | `GET /api/me`, `PUT /api/me/role`, `POST /api/auth/logout` | `role`은 `FOUNDER`, `OWNER`, `STUDENT` 중 하나. 예비 창업자는 `founderBudget`(원), 기존 사업자는 `businessNumber` |
| 상가 및 건축물 | `GET /api/catalog/shops`, `GET /api/catalog/building-register` | 상가 조회는 `west,south,east,north`, 페이지는 `page,size`. 상가 응답은 원본 `header`와 `body`. 건축물은 `sigunguCd,bjdongCd,bun,ji`와 선택 `landType,page,size` |
| 지도·공실 | `GET /api/buildings`, `GET /api/vacancies`, `GET /api/buildings/{id}/vacancies` | 공개 목록. 공실에는 확인 시각, 출처, 임대료·보증금이 포함되며 미제공 가격은 null |
| 주민 의견 | `GET/POST /api/buildings/{id}/opinions`, `GET /api/buildings/{id}/demand` | 등록은 로그인 후 `desiredBusiness,reason`. 수요 조회는 마지막 완료 결과를 배열로 반환 |
| 창업 계획 | `GET/POST /api/startup-plans`, `GET /api/startup-plans/{id}` | 생성은 모두 선택인 `businessIdea,preferredBuildingId,preferredAddress`. 건물 ID 또는 주소 중 하나만 입력 |
| 창업 입지·업종 분석 | `POST /api/startup-plans/{id}/analysis` | 아이템·위치 유무로 작업 유형 결정. 쿼리 `vacancyId`를 넣으면 선택한 공실과 건물로 분석 |
| 창업안 선택·상담 | `PUT /api/startup-plans/{id}/selection`, `POST /api/startup-plans/{id}/faq`, `POST /api/startup-plans/{id}/chat` | 선택은 `industry`와 `vacancyId`, `buildingId`, `address` 중 하나. 상담은 `message`와 선택 `previousJobId` |
| 기존 사업자 과제 정리 | `POST /api/owner/task-planning` | `industry,problem,goal`을 받아 과제 우선순위 제안 작업 등록 |
| 공고 | `POST /api/postings`, `GET /api/me/postings`, `GET /api/me/postings/{id}`, `PUT /api/postings/{id}` | `title,interest,workDescription,deliverable,periodWeeks,stipendKrw,availability`, 선택 `academicCredit`. 예비 창업자는 확정한 `startupPlanId` 필수 |
| 자동 초안과 승인 | `POST /api/postings/{id}/draft-generation`, `POST /api/postings/{id}/publish` | AI 초안 안내를 검토하고 수정 API로 저장한 뒤 별도 호출로 게시 |
| 학생 공고 조회 | `GET /api/postings`, `GET /api/postings/{id}` | 로그인 없이 읽기. 목록은 `keyword,interest,limit,offset` 사용 |
| 학생 추천 | `POST /api/postings/recommendations` | 학생 로그인 후 `interests,availability`로 AI 추천 작업 등록 |
| 학생 정보 | `GET/PUT /api/students/me` | `university,interests,availability`, 선택 `major,academicYear,advisor,linkedCourse` |
| 증빙 파일 | `PUT/GET /api/students/me/documents/{type}` | multipart 항목명 `file`, 종류 `ENROLLMENT`, `TRANSCRIPT`, `PORTFOLIO`. 8MB 이하 PDF/JPG/PNG. 다운로드는 본인만 가능 |
| 학생 지원 | `POST /api/postings/{id}/application-questions`, `POST /api/postings/{id}/applications`, `GET /api/me/applications` | 질문 작업 완료 후 `questionJobId,answers`로 답변 두 개 제출. 재학증명서 등록 필요 |
| 지원 조회·선택 | `GET /api/postings/{id}/applications`, `POST /api/postings/{id}/applications/{applicationId}/match` | 공고 작성자만 접근. 선택 필터는 `major,academicYear`. 공고당 한 명을 매칭 |
| 프로젝트와 경력 카드 | `GET /api/me/matches`, `GET /api/matches/{id}`, `POST /api/matches/{id}/start`, `POST /api/matches/{id}/complete`, `GET /api/students/me/career-cards` | 완료 입력은 `handoverNotes`와 선택 `handoverUrl`(HTTPS). 학생 경력 카드는 완료 건만 반환 |

사업자 상태 조회는 계속사업자 상태를 확인합니다. 계정 본인과 사업자 대표자의 일치 확인이 필요한 경우 국세청 진위확인에 필요한 정보를 추가로 받아야 합니다. 지도교수와 연계 교과는 현재 직접 입력입니다. 대학의 공식 배정, 학점 승인, 외부 알림은 별도 연동 대상입니다.

## 실제 공실 등록

운영자 작업에는 `X-V2V-Catalog-Token`이 필요합니다.

1. `POST /api/integrations/catalog/buildings`에 `buildingKey,roadAddress,longitude,latitude,source`를 보냅니다. `name,mainUse`는 선택입니다.
2. 임대인, 공인중개사 또는 현장 확인으로 공실이 확인되면 `POST /api/integrations/catalog/buildings/{id}/vacancies`에 `unitLabel,status,source,verifiedAt`을 보냅니다. `areaM2,depositKrw,monthlyRentKrw`는 확인된 값만 입력합니다. 공개 출처에는 확인 경로나 공개 URL을 쓰고 개인 연락처는 입력하지 않습니다.
3. 임대 진행이나 계약이 확인되면 `PUT /api/integrations/catalog/vacancies/{id}`로 정보를 갱신합니다. 상태는 `UNVERIFIED`, `AVAILABLE`, `IN_NEGOTIATION`, `CLOSED`입니다. 공개 목록과 추천 후보는 `AVAILABLE`만 반환합니다.
4. 게시판 의견을 모은 뒤 `POST /api/integrations/catalog/buildings/{id}/demand-analysis`로 최근 100건을 분석 요청합니다.

상가업소, 건축물대장 조회와 공실 등록은 서로 독립적입니다. 실제 공실 자동 확보는 임대 매물 제공처와의 연동 또는 지역 파트너의 데이터 등록이 필요합니다. 점포별 폐업 원인과 유동·매출 데이터는 현재 연결 범위에 포함되지 않습니다.

## AI 워커 연결

1. 프론트에서 분석 요청을 보내면 서버는 `202`와 `id,kind,status`를 응답합니다. 로그인한 요청자는 `GET /api/ai-jobs/{id}`로 결과를 조회합니다.
2. 워커가 `X-V2V-AI-Token`으로 `POST /api/integrations/ai/jobs/claim`을 호출합니다. 작업이 있으면 `id,kind,scopeId,input,leaseId,leaseExpiresAt`이 돌아옵니다. 큐가 비어 있으면 `204`입니다. 작업은 5분 안에 제출할 수 있고 이후에는 다른 워커가 다시 받을 수 있습니다.
3. 같은 헤더로 `PUT /api/integrations/ai/jobs/{id}/result`에 `leaseId,status,result`를 보냅니다. `status`는 `COMPLETED` 또는 `FAILED`이며 `result`는 JSON 객체입니다. `APPLICATION_QUESTIONS` 성공 응답의 필수 형식은 `{"questions":["질문 1","질문 2"]}`입니다.
4. 입지 분석에서 `input.nearbyShopQuery` 또는 `input.vacancies[].nearbyShopQuery`를 `GET /api/catalog/shops` 파라미터로 넘겨 주변 상가를 조회합니다. 공실 후보는 최근 확인순 100개까지 포함합니다. 입지와 업종 결론에는 실제 데이터 출처와 조회 시점, 자료가 부족할 때 판단하기 어려운 항목을 표시하는 것이 좋습니다.
5. 분석 위치가 정해졌으면 `input.areaStatistics`에 SGIS 행정동 통계를 담습니다. `censusYear`, `censusSummary`는 인구·가구·종사자와 기준연도입니다. `residentAgeProfile`은 거주자 연령대 집계이고 `industryBusinessCounts`는 반환된 최근 최대 3개 연도의 생활업종 사업체 수입니다. 상태는 `AVAILABLE`, `PARTIAL`, `NOT_CONFIGURED`, `UNAVAILABLE`입니다. 입점 후보를 지정한 경우 `input.vacancy`에 호실·면적·월세·보증금이 포함됩니다.

| 작업 종류 | 반환 결과 예시 키 |
| --- | --- |
| `STARTUP_RISK`, `STARTUP_LOCATION`, `STARTUP_INDUSTRY`, `STARTUP_COMMUNITY` | `findings`, `candidates`, `sources` |
| `STARTUP_FAQ`, `STARTUP_CHAT` | `items` 또는 `answer`, `sources` |
| `BUILDING_DEMAND` | `themes`, `opinionCount` |
| `OWNER_TASK_PLANNING` | `priorities`, `questions` |
| `POSTING_DRAFT` | 편집 화면에 붙일 `title`, `workDescription`, `deliverable` |
| `POSTING_RECOMMENDATIONS` | `recommendations`에 공고 ID와 추천 근거 |

테스트는 별도 메모리 DB를 사용합니다. 실서비스를 처음 시작하면 데이터는 비어 있으며 공실, 공고, 지원서는 실제 입력부터 표시됩니다.
