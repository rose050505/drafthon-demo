# 동성로 창업 위험 분석 서비스

V2V AI 워커가 이미 할당받은 `STARTUP_RISK` 작업을 보내면 반경 300m 경쟁 업종, 위험 근거, 창업 준비 사항과 학생 과제를 돌려줍니다. 상가 집계와 사업자 상황을 비교하며 연구용 모델의 폐업 확률은 검증 범위 부족으로 보류합니다.

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

### 배포된 서비스에 연결

운영 분석 주소는 `https://v2v-dongseongro-risk.onrender.com/api/startup-risk`입니다. 기존 V2V의 작업 할당에 `STARTUP_RISK`가 포함되도록, 원래 백엔드 서비스의 Render에서 PR #8의 복원 커밋 `6be17ce`를 배포합니다. 서비스의 Deploys 화면에서 배포 커밋과 상태를 확인할 수 있습니다. [Render 배포 안내](https://render.com/docs/deploys)

기존 AI 워커에 다음 환경 변수를 설정합니다.

| 이름 | 값 |
| --- | --- |
| `STARTUP_RISK_API_TOKEN` | 새 AI 서비스의 Render Environment에 설정된 같은 이름의 비밀 값 |
| `V2V_AI_WORKER_TOKEN` | 기존 V2V AI 워커가 작업 할당과 결과 저장에 쓰는 토큰 |
| `STARTUP_RISK_API_URL` | 생략하면 위 운영 분석 주소를 사용합니다 |
| `V2V_BACKEND_URL` | 생략하면 `https://v2v-drafthon-backend.onrender.com`을 사용합니다 |

`submit_claimed.py`를 기존 AI 워커의 실행 환경에 저장합니다. Python 3.10 이상에서 할당 응답 JSON을 표준 입력으로 넘겨 실행하면 분석 호출과 결과 저장을 모두 처리합니다.

```bash
python -B submit_claimed.py - < claimed-job.json
```

입력은 할당 API가 방금 반환한 원본 JSON을 사용합니다. `leaseId`가 일치하고 백엔드 저장이 HTTP 204로 성공했을 때 표준 출력에 작업 ID와 `COMPLETED`를 표시합니다. 기존 AI 워커에서 `STARTUP_RISK` 분기에 이 스크립트를 호출하면 이후 결과 저장도 스크립트가 담당합니다.

가공 데이터 출처는 소진공 상가정보, 대구교통공사 역별 하차 기록, 지방행정 음식점 인허가 집계입니다. [상가정보](https://www.data.go.kr/data/15083033/fileData.do) · [대구교통공사 승하차](https://www.data.go.kr/data/15002503/fileData.do) · [일반음식점 인허가](https://www.data.go.kr/data/15154916/openapi.do) · [휴게음식점 인허가](https://www.data.go.kr/data/15154921/openapi.do)
