// 모든 갈래를 끝까지 돌려 보고(미리 만들어 둔 답 모드) 화면을 캡처한다. 오류 · 숫자 대조 · 글자 넘침을 함께 점검.
// 사용: NODE_PATH=<playwright 있는 node_modules> node tools/shots.js [url] [폭x높이] [출력폴더]
const { chromium } = require('playwright');
const url = (process.argv[2] || 'http://localhost:8765/').replace(/#.*$/, '');
const [W, H] = (process.argv[3] || '1920x1080').split('x').map(Number);
const out = process.argv[4] || '캡처';
(async () => {
  const b = await chromium.launch({ executablePath: '/usr/bin/google-chrome' });
  const p = await b.newPage({ viewport: { width: W, height: H } });
  const errs = [];
  p.on('pageerror', e => errs.push(String(e))); p.on('console', m => { if (m.type() === 'error') errs.push(m.text()); });
  await p.goto(url + '#home'); await p.evaluate(() => localStorage.removeItem('dsr_key')); await p.reload();
  if (await p.locator('body > .warn').count()) console.log('점검 경고:', await p.textContent('body > .warn'));
  const shot = async n => { await p.waitForTimeout(250); await p.screenshot({ path: `${out}/${n}.png` }); };
  const clip = async tag => {
    const r = await p.evaluate(() => [...document.querySelectorAll('main *')].filter(e => e.scrollWidth > e.clientWidth + 2 && getComputedStyle(e).overflowX !== 'visible' && e.clientWidth > 0 && !e.closest('svg') && e.tagName !== 'PRE' && e.tagName !== 'SELECT').map(e => e.tagName + '.' + e.className + ':' + (e.textContent || '').slice(0, 30)));
    const wide = await p.evaluate(() => document.documentElement.scrollWidth > innerWidth + 2);
    const small = await p.evaluate(() => [...document.querySelectorAll('main *')].filter(e => !e.closest('svg') && !e.closest('details:not([open])') && e.childNodes.length && [...e.childNodes].some(n => n.nodeType === 3 && n.textContent.trim()) && parseFloat(getComputedStyle(e).fontSize) < 13).map(e => e.tagName + ':' + e.textContent.trim().slice(0, 20)).slice(0, 5));
    if (r.length || wide || small.length) console.log('[' + tag + '] 넘침', r.slice(0, 5), '가로스크롤', wide, '작은글씨', small);
  };
  const check = async (sel, tag) => console.log(tag, (await p.textContent(sel)).trim(), '| ✗', await p.locator('.ev .bad').count());
  const ex = async name => { await p.click(`[data-chat="${name}"][data-ex]`); await p.waitForTimeout(80); };

  await shot('01_첫화면'); await clip('home');
  // 신규 (가) 창업 아이템 보유
  await p.click('[data-hx="0"]'); await ex('newA'); await ex('newA'); await p.waitForSelector('#classOut h2');
  await shot('02_신규가_질의응답_분야분류'); await clip('newA1');
  await ex('newA'); await ex('newA'); await p.waitForSelector('#toPostA');
  await p.evaluate(() => document.querySelector('#designOut').scrollIntoView()); await shot('03_신규가_창업설계_자리비교'); await clip('newA2');
  await check('#designOut .numok, #designOut .numbad', '(가) 설계 숫자:');
  await p.click('#toPostA'); await p.waitForSelector('#approve'); await shot('04_A공고작성_B검토전'); await clip('postA');
  await p.click('#approve'); await p.waitForSelector('#toS5'); await shot('05_B게시완료_게시판');
  await p.click('#toS5'); await p.click('#askM'); await p.waitForSelector('.conf'); await p.click('.conf >> nth=0'); await p.waitForSelector('.done');
  await shot('06_지원자추천_담당자확인'); await clip('applyA');
  console.log('(가) 지원자 순위:', await p.$$eval('#mOut h4 .tag', e => e.map(x => x.textContent).join(' / ')));
  // 학생
  await p.click('#brand'); await p.click('[data-hx="3"]'); await p.click('#askStu'); await p.waitForSelector('.applyBtn');
  await shot('07_학생_경력되는공고추천'); await clip('student');
  // 기존 사장님
  await p.click('#brand'); await p.click('[data-hx="2"]'); await ex('old'); await p.waitForSelector('#diagOut h4'); await ex('old'); await p.waitForSelector('#toPostO');
  await shot('08_기존_질의응답_문제분석'); await clip('old');
  await check('#diagOut .numok, #diagOut .numbad', '기존 진단 숫자:');
  await p.evaluate(() => document.querySelector('.supbox').scrollIntoView()); await shot('08b_기존_받을수있는지원'); await clip('oldSup');
  console.log('기존 지원 판정:', await p.$$eval('#oldNext .verdict', e => e.map(x => x.textContent).join(' / ')));
  await p.click('#toPostSup'); await p.waitForSelector('#approve'); await shot('08c_지원사업_신청준비_공고'); await p.click('#approve'); await p.click('#toS5'); await p.click('#askM'); await p.waitForSelector('.conf');
  console.log('지원사업 공고 지원자:', await p.$$eval('#mOut h4 .tag', e => e.map(x => x.textContent).join(' / ')));
  await p.click('#brand'); await p.click('.door[data-go="old"]'); await p.waitForSelector('#toPostO');
  await p.click('.supbox [data-go="supRead"]'); await p.click('#askSup'); await p.waitForSelector('.suptab');
  await shot('14_AI_지원사업공고_구조화'); await clip('supRead'); console.log('공고 구조화:', await p.textContent('#supOut .numok, #supOut .numbad'));
  await p.click('#brand'); await p.click('.door[data-go="old"]'); await p.waitForSelector('#toPostO');
  await p.click('#toPostO'); await p.click('#approve'); await p.click('#toS5'); await p.click('#askM'); await p.waitForSelector('.conf');
  console.log('기존 지원자 순위:', await p.$$eval('#mOut h4 .tag', e => e.map(x => x.textContent).join(' / ')));
  // 신규 (나) 상권 기반 — 먼저 볼 점포 3곳 끝까지
  await p.click('#brand'); await p.click('.door[data-go="newSel"]'); await shot('09_신규_갈래고르기');
  await p.click('.door[data-go="nbMap"]'); await shot('10_신규나_상권지도'); await clip('nbMap');
  for (const i of [0, 1, 2]) {
    if (i) { await p.click('#brand'); await p.click('[data-hx="1"]'); }
    await p.click(`.pick button >> nth=${i}`); await p.click('#askRec'); await p.waitForSelector('.pickItem');
    if (!i) { await shot('11_신규나_자리분석_사업후보'); await clip('nbArea'); }
    await check('#recOut .numok, #recOut .numbad', `(나) 점포 ${i + 1} 숫자:`);
    await p.click('.pickItem >> nth=0'); await p.click('#askPrep'); await p.waitForSelector('.helpers');
    if (!i) { await shot('12_신규나_창업설계'); await clip('nbDesign'); }
    await p.click('#toS4'); await p.click('#approve'); await p.click('#toS5'); await p.click('#askM'); await p.waitForSelector('.conf');
  }
  await p.click('#brand'); await p.click('.door[data-go="howto"]'); await shot('13_이용방법'); await clip('howto');
  // 자유 입력 길 안내
  for (const [t, want] of [['저는 통계학과 학생이에요', 'student'], ['동성로에서 3년째 옷가게를 해요', 'old'], ['떡집을 차리고 싶어요', 'newA'], ['상권부터 보고 싶어요', 'nbMap']]) {
    await p.click('#brand'); await p.fill('#homeQ', t); await p.click('#homeAsk .btn'); await p.waitForTimeout(200);
    const h = await p.evaluate(() => location.hash.slice(1)); if (h !== want) console.log('길 안내 어긋남:', t, '→', h);
  }
  console.log('오류', errs.length ? errs : '없음');
  await b.close();
})().catch(e => { console.error('실패', e.message); process.exit(1); });
