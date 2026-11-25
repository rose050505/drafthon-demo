// 와이파이 · 노트북이 말썽일 때 틀 백업 영상: 미리 만들어 둔 답 모드로 시연순서.md 권장 경로를 약 40초에 자동으로 돌며 녹화한다.
// 사용: NODE_PATH=<playwright 있는 node_modules> node tools/record.js  -> 캡처/시연_백업.mp4
const { chromium } = require('playwright');
const path = require('path'), fs = require('fs'), { execSync } = require('child_process');
const root = path.join(__dirname, '..'), out = path.join(root, '캡처');
(async () => {
  const b = await chromium.launch({ executablePath: '/usr/bin/google-chrome' });
  const ctx = await b.newContext({ viewport: { width: 1920, height: 1080 }, recordVideo: { dir: out, size: { width: 1920, height: 1080 } } });
  const p = await ctx.newPage();
  await p.goto('file://' + path.join(root, 'index.html') + '#home');
  const w = ms => p.waitForTimeout(ms);
  const ex = async () => { await p.click('[data-chat="newA"][data-ex]'); await w(700); };
  const into = async sel => p.evaluate(s => document.querySelector(s).scrollIntoView({ behavior: 'smooth', block: 'start' }), sel);
  await w(5000);                                             // 0~5 첫 화면: 대화창 + 입구 4개
  await p.click('[data-hx="0"]'); await w(1200);            // 5~ 신규(가): 첫 질문에 답한 채로
  await ex(); await ex(); await w(2600);                      // ~10 분야 분류
  await ex(); await ex(); await w(1000);                      // AI 질문 · 마지막 질문 → C 창업 설계
  await into('#designOut'); await w(1500);
  await p.evaluate(() => document.querySelector('#designOut table').scrollIntoView({ behavior: 'smooth', block: 'center' })); await w(4200);   // ~20 세 자리 비교 + 추천 자리
  await p.click('#toPostA'); await w(3300);                  // ~24 A 공고 작성
  await p.click('#approve'); await w(2200);                   // ~26 B 검토 후 게시
  await p.click('#toS5'); await w(900);
  await p.click('#askM'); await w(4500);                      // ~32 지원자 추천: 규칙 4위 → AI 3위
  await p.click('#brand'); await w(700);                      // 학생 화면
  await p.click('[data-hx="3"]'); await w(900); await p.click('#askStu'); await w(8600);   // ~40
  const v = p.video(); await ctx.close(); await b.close();
  const src = await v.path(), webm = path.join(out, '시연_백업.webm'), mp4 = webm.replace('.webm', '.mp4');
  fs.renameSync(src, webm);
  try { execSync(`ffmpeg -y -loglevel error -i "${webm}" -c:v libx264 -pix_fmt yuv420p -r 30 "${mp4}"`); fs.unlinkSync(webm); console.log('녹화:', mp4); }
  catch (e) { console.log('mp4 변환 실패(ffmpeg) — webm 그대로:', webm); }
})();
