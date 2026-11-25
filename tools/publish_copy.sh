#!/bin/bash
# 시연 파일을 parkmiyeong.com/public/drafthon/ 에 복사 (원본 데이터 · 캡처 · 도구는 빼고). 배포는 parkmiyeong.com 저장소 커밋 · 푸시.
set -e
SRC="$(cd "$(dirname "$0")/.." && pwd)"; DST="$HOME/포트폴리오/parkmiyeong.com/public/drafthon"
mkdir -p "$DST/data"
cp "$SRC"/{app.js,calc.js,examples.js,pregen.js,support.js} "$DST/"
cp "$SRC/data/data.js" "$DST/data/"
sed 's#<meta name="color-scheme" content="light">#<meta name="color-scheme" content="light">\n<meta name="robots" content="noindex">#' "$SRC/index.html" > "$DST/index.html"
echo "복사 완료: $DST"
