# 동성로 창업 위험 분석 서비스

창업 입력을 직접 보내거나 V2V AI 워커가 이미 할당받은 `STARTUP_RISK` 작업을 보내면 반경 300m 경쟁 업종, 위험 근거, 창업 준비 사항과 학생 과제를 돌려줍니다. 상가 집계와 사업자 상황을 비교하며 연구용 모델의 폐업 확률은 검증 범위 부족으로 보류합니다.

## 워커 없이 직접 분석

이 폴더에서 `sample_direct.json`을 실제 후보 정보로 바꾸고 실행합니다. 직접 호출의 API 주소는 `https://v2v-dongseongro-risk.onrender.com/api/startup-risk/direct`이고, `POST` 요청 본문에 같은 JSON을 넣습니다.

```powershell
python -B analyze_direct.py sample_direct.json
```

Render에 설정된 `STARTUP_RISK_API_TOKEN`은 실행 시 숨김 입력으로 받거나 현재 터미널의 환경 변수로 전달합니다. 필수 항목은 `businessIdea`, `latitude`, `longitude`입니다. 업종을 알고 있으면 `industry`를 쓰고, 금액은 정수 원 단위로 입력합니다. 확인하지 않은 비용은 생략할 수 있습니다. 관리비가 0원으로 확인됐다면 `monthlyManagementKrw`에 0을 입력합니다. 응답의 `summary`, `findings`, `studentProjects`에서 판정, 조치 및 학생 과제를 볼 수 있습니다.

### 실제 V2V 창업 계획 분석

`sample_direct.json`을 별도 파일로 복사하고 후보지 위도·경도와 확인된 비용을 실제 값으로 바꿉니다. V2V 계획의 선택 업종을 사용하려면 복사한 파일의 `industry`를 생략합니다. 월세·관리비는 합산하고 월 예산과 비교합니다. 예산과 실제 비용은 원 단위 정수로 입력합니다.

```powershell
python -B analyze_direct.py my_case.json --v2v-login --output analysis.json
```

V2V 가입 이메일 또는 아이디와 비밀번호를 입력하면 운영 V2V에서 본인의 최근 계획을 조회합니다. 계획이 여러 개면 번호를 고르고, Render AI 서비스의 Environment에서 `STARTUP_RISK_API_TOKEN` 값을 확인해 숨김 입력에 넣습니다. V2V 로그인 정보는 V2V 서버에 보내고, AI 서비스에는 창업 분석 입력을 보냅니다. 호출 주소는 [운영 V2V Swagger](https://v2v-drafthon-backend.onrender.com/swagger-ui/index.html)와 [Render AI 서비스](https://dashboard.render.com/web/srv-dc3iurnlk1mc73f3dl5g)에서 확인할 수 있습니다.

`--output`을 지정하면 선택한 경로에 결과 JSON을 새로 만듭니다. 같은 이름의 파일이 있으면 새 파일명을 사용합니다. 옵션을 생략하면 JSON을 터미널에 출력합니다. 결과의 `planId`, `summary`, `findings`, `studentProjects`에서 계획, 판정, 준비 사항 및 과제를 확인합니다.

별도 옵션 `--set-founder`는 본인 V2V 계정의 활성 역할을 예비 창업자로 설정합니다. `--create-test-plan`은 본인 계획 목록이 비어 있을 때만 테스트용 창업 계획을 한 건 등록합니다. 두 옵션 모두 `--v2v-login`과 함께 사용합니다.

이미 계획 ID와 로그인 토큰을 알고 있으면 `--plan-id`와 `V2V_FOUNDER_TOKEN`으로 해당 계획을 직접 가져올 수 있습니다. 직접 분석의 결과는 API 응답과 선택한 출력 파일에서 확인합니다. 기존 V2V 작업 결과 저장은 아래 워커 연동 경로를 사용합니다.

## 실행

이 폴더에서 Python 3.10 이상으로 실행합니다.

```powershell
$env:STARTUP_RISK_API_TOKEN = '<이 서비스와 호출 워커가 공유할 비밀 값>'
python -B app.py
```

기본 주소는 `http://127.0.0.1:8765`입니다. `GET /health`가 `{"status":"UP"}`을 반환합니다. 테스트는 `python -B -m unittest -v`로 실행합니다. 별도 Docker 서비스에 올릴 때는 루트 디렉터리를 `startup-risk-worker`로, 헬스 체크 경로를 `/health`로 지정하고 공유 토큰을 환경 변수에 설정합니다.

## V2V 워커에서 사용

1. 기존 V2V 워커가 `POST /api/integrations/ai/jobs/claim`으로 작업을 받습니다.
2. `job.kind === "STARTUP_RISK"`인 경우 작업 전체를 이 서비스의 `POST /api/startup-risk`로 보냅니다. 요청 헤더 `Authorization: Bearer <STARTUP_RISK_API_TOKEN>`과 `Content-Type: application/json`을 사용합니다.
3. HTTP 200 응답 본문을 그대로 V2V의 `PUT /api/integrations/ai/jobs/{job.id}/result`에 보냅니다. 응답은 `leaseId`, `status`, `result`를 포함합니다. V2V 결과 저장에는 기존 워커 토큰인 `X-V2V-AI-Token`을 사용합니다.

V2V 할당 API는 여러 작업 유형이 공유합니다. 기존 워커에서 작업을 받은 후 유형을 분기하면 해당 작업의 할당 기한 안에 결과를 제출할 수 있습니다.

가공 데이터 출처는 소진공 상가정보, 대구교통공사 역별 하차 기록, 지방행정 음식점 인허가 집계입니다. [상가정보](https://www.data.go.kr/data/15083033/fileData.do) · [대구교통공사 승하차](https://www.data.go.kr/data/15002503/fileData.do) · [일반음식점 인허가](https://www.data.go.kr/data/15154916/openapi.do) · [휴게음식점 인허가](https://www.data.go.kr/data/15154921/openapi.do)
