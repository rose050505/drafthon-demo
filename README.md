# 동성로 일손 연결 AI — 드래프톤 시연 웹

2026 대구 대학(원) AI 드래프톤 발표용 시연. 동성로 빈 점포 · 가게 고민을 대학생 직무 경험으로 잇는 흐름을 보여 준다.

- **바로 보기**: https://parkmiyeong.com/drafthon/
- **내 컴퓨터에서**: `index.html` 더블클릭(인터넷 없이 돎). 또는 `python3 -m http.server 8765` 뒤 http://localhost:8765/

## 구조
| 파일 | 내용 |
|---|---|
| `index.html` | 화면 틀 · 스타일 |
| `app.js` | 화면 흐름(첫 화면 · 신규 가 · 신규 나 · 기존 · 학생 · 이용 방법), AI 호출 |
| `calc.js` | 자동 집계(반경 업종 · 비교) · 근거 숫자 대조 · 규칙 점수 |
| `pregen.js` | 미리 만들어 둔 AI 답(인터넷 · 키 없을 때) |
| `examples.js` | 가상 예시(사장님 · 학생 · 대화 질문) |
| `support.js` | 받을 수 있는 지원(실제 공고에서 확인한 값만) |
| `data/data.js` | 상가(상권)정보 2026년 2 · 3분기 비교 가공본 |
| `tools/` | `build_data.py`(데이터 다시 만들기) · `shots.js`(전체 자동 점검 · 캡처) · `record.js`(백업 영상) · `publish_copy.sh`(사이트 반영) |

## 문서
- `시연순서.md` — 40초 대본 · 질의 대비 · 녹화 방법
- `API_목록.md` — 끼울 API와 상태
- `REVIEW_LOG.md` — 검토 기록
- `디자인_참고_당직메이트.md` — 디자인 참고

## 데이터 다시 만들기
공공데이터포털 [상가(상권)정보 파일](https://www.data.go.kr/data/15083033/fileData.do)에서 대구 202606 · 202609 CSV를 받아 `data_src/`에 두고:
```bash
python3 -I tools/build_data.py data_src/*202606.csv data_src/*202609.csv
```

## 실시간 AI
사이트에서 오른쪽 위 점선 단추 → 팀 코드(미영에게 따로 받기) → 「팀 서버로 실시간 사용」. Claude 키는 서버(Vercel 환경 변수)에만 있고 이 저장소에는 없다.
