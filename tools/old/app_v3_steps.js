// 시연 화면. 숫자는 calc.js(공공데이터 자동 집계), 글은 AI(실시간 또는 시연용 미리 생성 결과).
(function () {
  const D = window.DATA, C = window.CALC, EX = window.EX, PRE = window.PREGEN || {};
  const $ = s => document.querySelector(s);
  const esc = s => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  const fmt = n => Number(n).toLocaleString('ko-KR');
  const sg = n => n > 0 ? '+' + n : n < 0 ? '−' + (-n) : '0';
  const pid = c => `${String(c.addr || '').replace('대구광역시 중구 ', '')}|${c.f}|${c.s}`;
  const short = a => String(a || '').replace('대구광역시 중구 ', '');
  const COL = { '소매': '#4C78A8', '음식': '#E8871E', '수리·개인': '#54A24B', '과학·기술': '#B279A2', '교육': '#3FA7A0',
    '시설관리·임대': '#9D755D', '보건의료': '#D64B57', '예술·스포츠': '#C9A400', '숙박': '#FF8FA0', '부동산': '#8C8C8C' };
  const STEPS = ['① 빈 점포 찾기', '② 자리 분석 · AI 사업 추천', '③ 사장님 진단 · 필요한 일손', '④ 공고 작성 · 승인', '⑤ 학생 지원 · 매칭'];
  const S = {
    step: 0, cand: null, R: 150, filt: { f1: true, dsr: false, hideFilled: true },
    rec: {}, entry: 'new', item: null, prep: {}, diag: {}, diagPoint: null, prof: EX.newOwner.profile, worry: EX.oldOwner.worry,
    post: {}, approved: [], lastPrompt: {}, students: EX.students.slice(), match: {}, confirmed: {}, postSel: null, postSrc: null
  };
  const featured = EX.featured.map(a => D.cands.find(c => c.addr === a)).filter(Boolean);
  S.diagPoint = featured[1] || featured[0];

  // ---------- AI 연결 ----------
  const COMMON = '너는 대구시 동성로 상권 지원 담당자를 돕는 AI다. 사장님 · 대학생이 읽을 쉬운 한국어로 쓴다. ' +
    '반드시 JSON 객체 하나만 출력한다(앞뒤 설명 · 코드블록 표시 금지). ' +
    '숫자는 입력의 [계산값]에 있는 숫자만 그대로 인용한다. 매출 · 임대료 · 비율 · 인구 같은 새 숫자를 만들지 않는다. ' +
    '손님 나이 · 시간대 · 유동 인구처럼 계산값에 없는 내용은 단정하지 말고 「현장 확인 필요」라고 쓴다. ' +
    '한 분기 증감은 추세가 아니다: 「줄어드는 · 늘고 있는 업종」 대신 「지난 분기 ○곳 → ○곳」으로 쓴다. ' +
    '단정하지 말고 후보와 근거를 제시한다. 결정은 사람(사장님 · 담당자)이 한다.';
  const live = () => !!localStorage.getItem('dsr_key');
  const modelName = () => localStorage.getItem('dsr_model') || 'claude-opus-5-5';
  function setMode() {
    const b = $('#modeBtn');
    if (live()) { b.className = 'mode live'; b.textContent = 'AI: 실시간 연결 (' + modelName() + ')'; }
    else { b.className = 'mode pre'; b.textContent = 'AI: 미리 만들어 둔 답 (인터넷 없이)'; }
  }
  function parseJSON(t) {
    t = t.replace(/```json|```/g, '');
    const a = t.indexOf('{'), b = t.lastIndexOf('}');
    return JSON.parse(t.slice(a, b + 1));
  }
  async function callClaude(system, user, maxTokens, timeoutMs, keyOverride, modelOverride) {
    const ctl = new AbortController(); const tm = setTimeout(() => ctl.abort(), timeoutMs || 20000);
    try {
      const r = await fetch('https://api.anthropic.com/v1/messages', {
        method: 'POST', signal: ctl.signal,
        headers: {
          'content-type': 'application/json', 'x-api-key': keyOverride || localStorage.getItem('dsr_key'),
          'anthropic-version': '2023-06-01', 'anthropic-dangerous-direct-browser-access': 'true',
          'anthropic-beta': 'server-side-fallback-2026-07-01'
        },
        body: JSON.stringify({
          model: modelOverride || modelName(), max_tokens: maxTokens || 8000,
          output_config: { effort: 'low' }, fallbacks: 'default',
          system: system || undefined, messages: [{ role: 'user', content: user }]
        })
      });
      let j; try { j = await r.json(); } catch (e) { throw new Error('응답이 AI 답 형식이 아님(HTTP ' + r.status + ') — 와이파이 로그인 화면일 수 있음'); }
      if (!r.ok) throw new Error(r.status === 401 ? 'API 키가 틀림' : r.status === 429 ? '요청이 너무 많음(잠시 뒤 다시)' : r.status >= 500 ? 'AI 서버 혼잡' : '요청 오류(HTTP ' + r.status + ')');
      if (j.stop_reason === 'refusal') throw new Error('AI가 답하지 않음');
      if (j.stop_reason === 'max_tokens') throw new Error('답이 길어 잘림');
      const text = (j.content || []).filter(b => b.type === 'text').map(b => b.text).join('');
      let out = text;
      if (system) { try { out = parseJSON(text); } catch (e) { throw new Error('AI 답 형식 오류'); } }
      return { out, model: j.model };
    } catch (e) {
      if (e.name === 'AbortError') throw new Error('응답 시간 초과');
      if (e instanceof TypeError) throw new Error('인터넷 연결 끊김');
      throw e;
    } finally { clearTimeout(tm); }
  }
  // 실시간 → 실패 시 미리 생성 결과. preKey 가 null 이면 미리 생성 결과를 쓰지 않음(입력이 바뀐 경우)
  const isArr = a => Array.isArray(a);
  const SHAPE = {
    rec: o => isArr(o.candidates) && o.candidates.every(k => k && typeof k.biz === 'string'),
    prep: o => isArr(o.stages) && o.stages.every(x => isArr(x.items)) && isArr(o.helpers) && isArr(o.consider || []),
    diag: o => isArr(o.issues) && isArr(o.helpers),
    post: o => typeof o.title === 'string' && isArr(o.tasks) && isArr(o.majors) && isArr(o.keywords || []),
    match: o => isArr(o.ranked) && o.ranked.some(r => S.students.some(s => s.id === r.id))
  };
  async function getAI(preKey, system, user, kind) {
    if (live()) {
      const t0 = Date.now();
      try {
        const r = await callClaude(system, user);
        if (kind && !(r.out && SHAPE[kind](r.out))) throw new Error('AI 답 형식 오류');
        if (kind === 'match') r.out.ranked = r.out.ranked.filter(x => S.students.some(s => s.id === x.id));
        return { out: r.out, src: 'live', model: r.model, sec: Math.round((Date.now() - t0) / 1000) };
      }
      catch (e) { if (preKey && PRE[preKey]) return { out: PRE[preKey], src: 'pre', err: String(e.message || e) }; return { err: String(e.message || e), src: 'none' }; }
    }
    if (preKey && PRE[preKey]) { await new Promise(r => setTimeout(r, 300)); return { out: PRE[preKey], src: 'pre' }; }
    return { src: 'none' };
  }
  const hasPre = k => !!PRE[k];
  function badge(res) {
    if (res.src === 'live') return `<span class="badge live">AI 실시간 생성 · ${esc(res.model || modelName())} · ${res.sec}초</span>`;
    let t = '<span class="badge pre">AI 답 · 미리 만들어 둠 — Claude가 같은 숫자를 받아 작성 (실시간 연결 때는 새로 생성)</span>';
    if (res.err) t += `<div class="note">실시간 연결 실패(${esc(res.err)}) → 미리 만들어 둔 답으로 대신 표시</div>`;
    return t;
  }
  function noneMsg(res, why) {
    const head = res && res.err ? `실시간 AI 호출 실패: ${esc(res.err)}<br>` : '';
    const tail = live() ? '인터넷 연결과 API 키를 확인하세요.' : '오른쪽 위 「AI」 단추에서 API 키를 넣으면 실시간으로 생성합니다.';
    return `<div class="warn">${head}${why} — 미리 만들어 둔 답이 없습니다. ${tail}<br>미리 만들어 둔 경로: ①의 「시연 추천 점포」 3곳 → 반경 150m → 첫 번째 추천 사업 / (나) 기본 고민 글.</div>`;
  }
  // 답이 늦게 와도 화면이 바뀌었으면 그리지 않는다
  let ticket = 0;
  const still = (t, step, sel) => t === ticket && S.step === step && $(sel);

  // ---------- 지도 (오프라인 SVG) ----------
  const INS = D.active.filter(p => p.in);
  const ALL = INS.map(p => C.xy(p));
  function pct(arr, q) { const s = arr.slice().sort((a, b) => a - b); return s[Math.floor(q * (s.length - 1))]; }
  const BX = [pct(ALL.map(p => p[0]), 0.005) - 60, pct(ALL.map(p => p[0]), 0.995) + 60];
  const BY = [pct(ALL.map(p => p[1]), 0.005) - 60, pct(ALL.map(p => p[1]), 0.995) + 60];
  function scaleBar(x, y, m, k) {
    return `<g font-size="${16 * k}" fill="#333"><line x1="${x}" y1="${y}" x2="${x + m}" y2="${y}" stroke="#333" stroke-width="${3 * k}"/>` +
      `<text x="${x + m / 2}" y="${y - 7 * k}" text-anchor="middle">${m}m</text></g>`;
  }
  function label(x, y, t, k, size) {
    return `<text x="${x}" y="${y}" font-size="${size * k}" fill="#14284b" font-weight="800" text-anchor="middle" paint-order="stroke" stroke="#fff" stroke-width="${6 * k}" stroke-linejoin="round" pointer-events="none">${esc(t)}</text>`;
  }
  function fullMap() {
    const w = BX[1] - BX[0], h = BY[1] - BY[0], k = Math.max(w / 900, h / 820);
    const vis = visibleCands();
    let s = `<svg id="map" viewBox="${BX[0]} ${BY[0]} ${w} ${h}" role="img" aria-label="동성로 업소 분포 지도">`;
    s += INS.map(p => { const [x, y] = C.xy(p); return `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="${2.3 * k}" fill="${p.dsr ? '#7d8796' : '#b5bcc6'}"/>`; }).join('');
    s += vis.map(c => {
      const [x, y] = C.xy(c); const on = S.cand && S.cand.id === c.id, fi = featured.indexOf(c);
      if (fi >= 0) return '';
      return `<circle class="cd" data-id="${c.id}" cx="${x}" cy="${y}" r="${(on ? 7 : 4.2) * k}" fill="${on ? '#c62828' : 'rgba(198,40,40,.65)'}" stroke="#7a0000" stroke-width="${1 * k}" style="cursor:pointer"><title>${esc(short(c.addr))} · ${c.f ? c.f + '층' : '층 정보 없음'} · 직전 ${esc(c.s)}</title></circle>`;
    }).join('');
    for (const [n, v] of Object.entries(D.lm)) { const [x, y] = C.xy({ x: v[0], y: v[1] }); s += label(x, y, n, k, 17); }
    s += featured.map((c, fi) => {
      const [x, y] = C.xy(c);
      return `<circle class="cd" data-id="${c.id}" cx="${x}" cy="${y}" r="${9 * k}" fill="#c62828" stroke="#fff" stroke-width="${2.5 * k}" style="cursor:pointer"><title>${esc(short(c.addr))}</title></circle>` +
        `<text x="${x + 12 * k}" y="${y + 9 * k}" font-size="${28 * k}" font-weight="900" fill="#c62828" paint-order="stroke" stroke="#fff" stroke-width="${6 * k}" pointer-events="none">${'①②③'[fi]}</text>`;
    }).join('');
    s += scaleBar(BX[0] + 30 * k, BY[1] - 30 * k, 200, k);
    s += `<text x="${BX[1] - 30 * k}" y="${BY[0] + 40 * k}" font-size="${20 * k}" text-anchor="middle" font-weight="700">↑북</text></svg>`;
    return s;
  }
  function zoomMap(c, R) {
    const [cx, cy] = C.xy(c), half = R * 1.45, k = half * 2 / 470;
    let s = `<svg class="zm" viewBox="${cx - half} ${cy - half} ${half * 2} ${half * 2}" role="img" aria-label="반경 지도">`;
    s += `<circle cx="${cx}" cy="${cy}" r="${R}" fill="rgba(31,95,191,.06)" stroke="#1f5fbf" stroke-width="${2 * k}" stroke-dasharray="${8 * k} ${6 * k}"/>`;
    for (const p of D.active) {
      const [x, y] = C.xy(p); if (Math.abs(x - cx) > half || Math.abs(y - cy) > half) continue;
      s += `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="${4 * k}" fill="${COL[p.l] || '#888'}" opacity=".85"/>`;
    }
    for (const g of D.cands) {
      const [x, y] = C.xy(g); if (Math.abs(x - cx) > half || Math.abs(y - cy) > half || g.id === c.id) continue;
      s += `<circle cx="${x}" cy="${y}" r="${5 * k}" fill="none" stroke="#c62828" stroke-width="${2 * k}"/>`;
    }
    s += `<circle cx="${cx}" cy="${cy}" r="${11 * k}" fill="#c62828" stroke="#fff" stroke-width="${3 * k}"/>`;
    s += scaleBar(cx - half + 20 * k, cy + half - 22 * k, R >= 300 ? 100 : 50, k);
    s += `<rect x="${cx + half - 62 * k}" y="${cy - half + 10 * k}" width="${52 * k}" height="${34 * k}" fill="#fff" opacity=".85"/><text x="${cx + half - 36 * k}" y="${cy - half + 34 * k}" font-size="${18 * k}" text-anchor="middle" font-weight="700">↑북</text></svg>`;
    return s;
  }
  function legend(withCand) {
    return `<div class="legend">${Object.entries(COL).map(([k, v]) => `<span><i style="background:${v}"></i>${k}</span>`).join('')}` +
      (withCand ? `<span><i style="background:none;border:2px solid #c62828"></i>다른 사라진 업소</span>` : '') + '</div>';
  }
  function visibleCands() {
    return D.cands.filter(c => c.in && (!S.filt.f1 || c.f === '1') && (!S.filt.dsr || c.dsr) && (!S.filt.hideFilled || !c.filled.length));
  }

  // ---------- 화면 ----------
  function nav() {
    $('#nav').innerHTML = STEPS.map((t, i) => `<button data-s="${i}" class="${S.step === i ? 'on' : ''}">${t}</button>`).join('');
    document.querySelectorAll('#nav button').forEach(b => b.onclick = () => go(+b.dataset.s));
  }
  function go(i, fromPop) { ticket++; S.step = i; nav(); [step1, step2, step3, step4, step5][i](); window.scrollTo(0, 0); if (!fromPop) history.pushState({ s: i }, '', '#' + (i + 1)); }
  window.addEventListener('popstate', e => go(e.state && e.state.s != null ? e.state.s : 0, true));
  const srcTag = () => `<div class="srcband"><span class="tag real">실제 데이터</span> 상가(상권)정보 ${esc(D.meta.q_prev)} · ${esc(D.meta.q_now)} 비교 · ${esc(D.meta.scope)}</div>`;
  const srcLine = () => `<div class="src">출처: ${esc(D.meta.src)}. 같은 업소가 ${esc(D.meta.q_now)} 상가정보에 없으면 「사라진 업소」로 셈(같은 건물에 이름이 같은 업소가 남아 있으면 이름 변경 · 중복으로 보고 뺌).</div>`;

  function step1() {
    const m = D.meta, vis = visibleCands();
    $('#main').innerHTML = `<div class="grid g1">
      <div class="card"><h2>동성로 업소 지도 — 회색 점: 영업 중 ${fmt(m.n_now)}곳 · 빨간 점: 빈 점포 후보</h2>
        <div id="mapWrap">${fullMap()}</div>
        <div class="legend"><span><i style="background:#7d8796"></i>「동성로」 길 주소 업소</span><span><i style="background:#b5bcc6"></i>그 밖의 성내1동 업소</span><span><i style="background:rgba(198,40,40,.8)"></i>빈 점포 후보 (점을 누르면 선택)</span></div>
      </div>
      <div class="card">
        <h2>지난 분기에 있다가 이번 분기에 사라진 업소</h2>${srcTag()}
        <div class="big">
          <div><b>${fmt(m.n_prev)} → ${fmt(m.n_now)}</b><span>영업 중 업소 (2분기 → 3분기)</span></div>
          <div><b>${fmt(m.n_gone)}곳</b><span>사라진 업소 (새로 생김 ${fmt(m.n_new)}곳)</span></div>
          <div><b class="red">${fmt(m.n_floor1_open)}곳</b><span>그중 1층 · 같은 층에 새 업소 없음<br>= 먼저 볼 빈 점포 후보</span></div>
        </div>
        <p class="note">${fmt(m.n_prev)} − 사라짐 ${fmt(m.n_gone)} − 중복 기록 정리 ${fmt(m.n_dup)} + 새로 생김 ${fmt(m.n_new)} = ${fmt(m.n_now)}. 이름 · 번호만 바뀐 같은 업소 ${fmt(m.n_kept)}곳은 사라짐에서 뺐습니다. 사라진 업소 비율: 동성로 범위 ${m.rate_scope.toFixed(1)}% · 대구 전체 ${m.rate_daegu.toFixed(1)}% (같은 규칙).</p>
        <p class="note">참고(공식 통계): 동성로 중대형 상가 공실률 26.2% — 면적 기준, 2026년 3분기(한국부동산원, 경북매일 26-11-17 보도). 이 화면은 그 빈 곳이 <b>어디일 수 있는지</b>를 업소 기록으로 찾습니다.</p>
        <div class="warn">「사라진 업소」는 실제 공실이 아니라 빈 점포 <b>후보</b>입니다. 비었는지는 현장 · 건물주 확인이 필요합니다(층 정보 없는 곳 ${m.n_floor_unknown}곳 포함).</div>
        <div class="row" style="margin:.4rem 0">
          <label class="chk"><input type="checkbox" id="f1" ${S.filt.f1 ? 'checked' : ''}>1층만</label>
          <label class="chk"><input type="checkbox" id="fHide" ${S.filt.hideFilled ? 'checked' : ''}>같은 층에 새 업소 들어온 곳 빼기</label>
          <label class="chk"><input type="checkbox" id="fDsr" ${S.filt.dsr ? 'checked' : ''}>「동성로」 길 주소만</label>
        </div>
        <p style="margin:.2rem 0 .6rem;font-size:1.05rem">지도에 보이는 후보: <b>${fmt(vis.length)}곳</b></p>
        <h3>시연 추천 점포 (1층 · 동성로 길 · 같은 건물에 새 업소 없음)</h3>
        <div class="pick">${featured.map((c, i) => `<button data-id="${c.id}" class="${S.cand && S.cand.id === c.id ? 'on' : ''}"><b>${'①②③'[i]} ${esc(short(c.addr))}</b><small>${c.f}층 · 직전 업종: ${esc(c.s)}</small></button>`).join('')}</div>
        <div id="sel"></div>
        <h3>사라진 ${fmt(m.n_gone)}곳의 직전 업종 (많은 순)</h3>
        <table><tr><th>직전 업종</th><th>2분기</th><th>사라짐</th><th>새로</th><th>기록 정리*</th><th>3분기</th></tr>${Object.entries(D.sub).sort((a, b) => b[1][2] - a[1][2] || b[1][0] - a[1][0]).slice(0, 5).map(([k, v]) => `<tr><td>${esc(k)}</td><td>${v[0]}</td><td>${v[2] ? '−' + v[2] : 0}</td><td>${v[3] ? '+' + v[3] : 0}</td><td>${sg(v[4])}</td><td>${v[1]}</td></tr>`).join('')}</table>
        <p class="note">* 기록 정리 = 같은 업소의 중복 기록 정리 · 업종 분류 변경 (2분기 − 사라짐 + 새로 + 기록 정리 = 3분기)</p>
        ${srcLine()}
      </div></div>`;
    $('#fHide').onchange = e => { S.filt.hideFilled = e.target.checked; step1(); };
    $('#f1').onchange = e => { S.filt.f1 = e.target.checked; step1(); };
    $('#fDsr').onchange = e => { S.filt.dsr = e.target.checked; step1(); };
    document.querySelectorAll('.pick button').forEach(b => b.onclick = () => { pickCand(b.dataset.id); go(1); });
    document.querySelectorAll('#map .cd').forEach(el => el.onclick = () => { pickCand(el.dataset.id); step1(); });
    if (S.cand) {
      const c = S.cand, pre = hasPre(`rec:${pid(c)}:150`);
      $('#sel').innerHTML = `<div class="rec" style="margin-top:.8rem"><h4>선택한 점포: ${esc(short(c.addr))}</h4>
        <p>${c.f ? c.f + '층' : '층 정보 없음'} · 직전 업종: <b>${esc(c.s)}</b>${c.bld ? ' · ' + esc(c.bld) : ''}</p>
        ${c.filled.length ? `<p class="numbad">같은 층에 3분기 새 업소 있음: ${esc(c.filled.join(', '))} — 이미 채워졌을 수 있음</p>` : ''}
        ${!c.f && c.bnew ? `<p class="numbad">층 정보 없음 · 같은 건물에 3분기 새 업소 ${c.bnew}곳 — 이미 채워졌을 수 있음</p>` : ''}
        ${c.moved ? `<p class="note">같은 이름 업소가 3분기에 근처(${esc(short(c.moved))})에 새로 생김 — 이 자리에서 옮겨 갔을 수 있음</p>` : ''}
        ${!pre && !live() ? '<p class="note">이 점포는 반경 숫자는 바로 나오고, AI 답은 실시간 연결 때만 나옵니다.</p>' : ''}
        <button class="btn" id="toS2">② 이 자리 분석하기 →</button></div>`;
      $('#toS2').onclick = () => go(1);
    }
  }
  function pickCand(id) { const c = D.cands.find(c => c.id === id); if (!S.cand || S.cand.id !== c.id) { S.cand = c; S.item = null; } }

  function needCand() {
    $('#main').innerHTML = `<div class="card"><h2>먼저 ①에서 빈 점포 후보를 고르세요</h2><button class="btn" id="b">① 빈 점포 찾기로</button></div>`;
    $('#b').onclick = () => go(0);
  }

  // ---------- ② ----------
  const recKey = () => `rec:${pid(S.cand)}:${S.R}`;
  function step2() {
    const c = S.cand; if (!c) return needCand();
    const f = C.facts(c, S.R), maxL = f.대분류별[0] ? f.대분류별[0].업소수 : 1, key = recKey();
    $('#main').innerHTML = `<div class="grid g2">
      <div class="card"><h2>${esc(short(c.addr))} · 반경 ${S.R}m</h2>
        ${zoomMap(c, S.R)}${legend(true)}
        <div class="row" style="margin-top:.6rem">반경 <button class="btn ${S.R === 150 ? '' : 'ghost'}" data-r="150">150m</button><button class="btn ${S.R === 300 ? '' : 'ghost'}" data-r="300">300m</button></div>
      </div>
      <div class="card"><h2>공공데이터로 자동 집계한 숫자</h2>${srcTag()}
        <p style="margin:.3rem 0 .5rem">${c.f ? c.f + '층' : '층 정보 없음'} · 직전 업종 <b>${esc(c.s)}</b> (2분기에 있다가 3분기에 사라짐)</p>
        <div class="big">
          <div><b>${fmt(f.반경안_영업중_업소수)}곳</b><span>반경 안 영업 중 (지난 분기 ${fmt(f.반경안_지난분기_업소수)})</span></div>
          <div><b class="red">${fmt(f.반경안_사라진업소수)}곳</b><span>사라짐(이 점포 포함) · 새로 ${fmt(f.반경안_새로생긴업소수)}곳 · 기록 정리 ${sg(f.반경안_기록정리)}</span></div>
        </div>
        <h3>업종 큰 분류별 업소 수</h3>
        <div class="bars">${f.대분류별.map(r => `<div class="bar"><span>${esc(r.대분류)}</span><div style="width:${Math.max(2, 100 * r.업소수 / maxL)}%;background:${COL[r.대분류]}"></div><span>${r.업소수}</span></div>`).join('')}</div>
        <h3>반경 안 많은 업종 (같은 업종 밀도)</h3>
        <table><tr><th>업종</th><th>지난 분기</th><th>사라짐</th><th>새로</th><th>기록 정리*</th><th>지금</th></tr>
        ${f.많은업종_상위12.map(r => `<tr class="${r.업종 === c.s ? 'hl' : ''}"><td>${esc(r.업종)}</td><td>${r.지난분기}</td><td>${r.사라짐 ? '−' + r.사라짐 : 0}</td><td>${r.새로생김 ? '+' + r.새로생김 : 0}</td><td>${sg(r.기록정리)}</td><td><b>${r.지금}</b></td></tr>`).join('')}</table>
        <p class="note">직전 업종(${esc(c.s)}) — 반경 안 지금 ${f.직전업종_반경안_지금}곳 · 동성로 범위 전체 ${f.직전업종_동성로범위.지난분기}곳 → ${f.직전업종_동성로범위.지금}곳 (사라짐 ${f.직전업종_동성로범위.사라짐}, 새로 ${f.직전업종_동성로범위.새로생김}, 기록 정리 ${sg(f.직전업종_동성로범위.기록정리)}). * 기록 정리 = 같은 업소의 중복 기록 정리 · 업종 분류 변경. 업소 수는 상가정보 기록 기준.</p>
        ${srcLine()}
      </div>
      <div class="card ai"><h2>AI: 이 자리에 맞는 사업 후보 3개</h2>
        <p class="note"><b>자동 집계</b>는 업종 개수 · 증감만 셉니다. <b>AI</b>는 그 숫자를 읽고 자리 성격 · 직전 가게의 매장 구조 · 위험을 판단해 말로 설명합니다. AI는 근거 숫자를 항목 이름과 함께 내고, 화면이 그 항목의 집계값과 하나씩 대조합니다.<br>이 판단에 쓰지 않은 자료: 임대료 · 유동 인구 · 카드 매출 → 본 사업에서 연결할 자료.</p>
        <div id="recOut">${S.rec[key] ? '' : `<button class="btn" id="askRec">AI에게 사업 후보 묻기</button>${!live() && !hasPre(key) ? '<p class="note">이 점포 · 반경은 미리 만들어 둔 답이 없어 실시간 연결 때만 AI 답이 나옵니다.</p>' : ''}`}</div>
      </div></div>`;
    document.querySelectorAll('[data-r]').forEach(b => b.onclick = () => { S.R = +b.dataset.r; S.item = null; step2(); });
    if (S.rec[key]) showRec(c, f, S.rec[key]);
    else $('#askRec').onclick = e => { e.target.disabled = true; askRec(c, f, key); };
  }
  function scopeSubTable() {
    return Object.entries(D.sub).filter(([k, v]) => v[1] >= 5 || v[2] >= 2).sort((a, b) => b[1][1] - a[1][1])
      .map(([k, v]) => `${k}: 지난분기 ${v[0]} / 지금 ${v[1]} / 사라짐 ${v[2]} / 새로생김 ${v[3]}`).join('\n');
  }
  async function askRec(c, f, key) {
    const t = ticket;
    $('#recOut').innerHTML = '<div class="spin">AI가 집계 숫자를 읽고 있습니다…</div>';
    const user = recPrompt(c, f);
    S.lastPrompt[key] = user;
    const res = await getAI(key, COMMON, user, 'rec');
    if (res.src === 'none') { if (still(t, 1, '#recOut')) { $('#recOut').innerHTML = noneMsg(res, '이 점포 · 반경') + '<button class="btn" id="askRec">다시 시도</button>'; $('#askRec').onclick = () => askRec(c, f, key); } return; }
    S.rec[key] = res;
    if (still(t, 1, '#recOut') && recKey() === key) showRec(c, f, res);
  }
  function recPrompt(c, f) {
    return `대구 동성로의 빈 점포 후보 한 곳과 그 주변을 자동 집계한 값이다(실제 공공데이터).\n[계산값]\n${JSON.stringify(f, null, 1)}\n\n[참고: 동성로 범위 전체 업종별 업소 수 (${D.meta.q_prev} → ${D.meta.q_now})]\n${scopeSubTable()}\n\n` +
      `할 일: 이 자리에 맞는 사업 후보 3개를 골라라. 같은 업종뿐 아니라 「비슷한업종묶음」(예: 기타 간이 = 분식 · 빵 · 토스트 · 버거)도 보고 경쟁을 판단한다. 같은 업종이 몰려 있거나 사라짐이 많은 업종은 위험으로 적는다. 직전 가게의 업종으로 매장 구조(주방 유무 등)를 짐작해 공사 부담도 적는다. 맞는 사업이 없다고 판단되면 그렇게 써도 된다.\n` +
      `숫자는 이 점포 [계산값]과 네가 고른 업종의 수치만 쓴다. 문장에 쓴 숫자는 evidence 에 항목 이름(key)과 값으로 모두 적는다. key 는 「${EVKEYS.join(' · ')}」 중 하나(고른 업종 기준)이거나 [계산값]의 맨 위 항목 이름이다.\n` +
      `출력 JSON: {"summary":"이 자리 한 줄 요약","candidates":[{"biz":"사업 이름(사장님이 알아듣는 말)","code":"참고 목록의 업종 이름 하나(그대로 복사)","why":"맞는 이유 2문장","risk":"위험 1~2문장","check":"사장님이 현장에서 확인할 것 1문장","evidence":[{"key":"반경안_지금","value":0}]}],"avoid":{"biz":"권하지 않는 업종","code":"참고 목록의 업종 이름","why":"이유 1문장","evidence":[]}}`;
  }
  const EVKEYS = ['반경안_지금', '반경안_지난분기', '반경안_사라짐', '반경안_새로생김', '반경안_비슷한업종', '범위_지난분기', '범위_지금', '범위_사라짐', '범위_새로생김', '범위_기록정리'];
  const EVLABEL = { 반경안_지금: '반경 안 같은 업종', 반경안_지난분기: '반경 안 같은 업종(지난 분기)', 반경안_사라짐: '반경 안 같은 업종 사라짐', 반경안_새로생김: '반경 안 같은 업종 새로', 반경안_비슷한업종: '반경 안 비슷한 업종', 범위_지난분기: '동성로 범위(지난 분기)', 범위_지금: '동성로 범위(지금)', 범위_사라짐: '동성로 범위 사라짐', 범위_새로생김: '동성로 범위 새로', 범위_기록정리: '동성로 범위 기록 정리', 반경안_사라진업소수: '반경 안 사라진 업소', 반경안_새로생긴업소수: '반경 안 새 업소', 반경안_영업중_업소수: '반경 안 업소', '직전업종_동성로범위.지난분기': '직전 업종 범위(지난 분기)', '직전업종_동성로범위.지금': '직전 업종 범위(지금)', '직전업종_동성로범위.사라짐': '직전 업종 범위 사라짐', '직전업종_동성로범위.새로생김': '직전 업종 범위 새로', '직전업종_동성로범위.기록정리': '직전 업종 범위 기록 정리', '같은업종.범위_지난분기': '같은 업종 범위(지난 분기)', '같은업종.범위_지금': '같은 업종 범위(지금)', '같은업종.범위_새로생김': '같은 업종 범위 새로', '같은업종.반경안_지난분기': '반경 안 같은 업종(지난 분기)' };
  const evLabel = k => EVLABEL[k] || EVLABEL[k.split('.').pop()] || k.replace(/_/g, ' ');
  function evHTML(chk) {
    if (!chk.length) return '';
    return `<p class="ev">근거 숫자 대조: ${chk.map(e => `<span class="${e.ok ? 'ok' : 'bad'}">${esc(evLabel(e.key))} ${esc(e.value)} ${e.ok ? '✓' : '✗' + (e.truth != null ? '(집계 ' + e.truth + ')' : '(없는 항목)')}</span>`).join(' · ')}</p>`;
  }
  function promptBox(key) {
    const t = S.lastPrompt[key]; if (!t) return '';
    return `<details class="pbox"><summary>AI에게 보낸 내용 보기 (자동 집계 숫자 + 지시)</summary><pre>${esc(COMMON + '\n\n' + t)}</pre></details>`;
  }
  function allowedFor(c, R, f, o) {
    const allowed = C.numbersIn(f); allowed.add(String(R));
    (o.candidates || []).concat(o.avoid ? [o.avoid] : []).forEach(k => { const sf = C.subFacts(c, R, k.code); if (sf) C.numbersIn(sf, allowed); });
    return allowed;
  }
  function numBadge(chk) {
    return chk.bad.length ? `<div class="numbad">⚠ 문장 속 숫자 중 이 점포 집계표에 없는 값: ${chk.bad.map(esc).join(', ')} — 사람이 확인 필요</div>`
      : chk.total ? `<div class="note">문장 속 숫자 ${chk.total}개도 모두 이 점포 집계표 안의 값</div>` : '<div class="note">AI 답에 숫자 없음</div>';
  }
  function showRec(c, f, res) {
    const o = res.out;
    if (!S.lastPrompt[recKey()]) S.lastPrompt[recKey()] = recPrompt(c, f);
    const chk = C.checkNumbers(JSON.stringify([o.summary].concat((o.candidates || []).map(k => [k.why, k.risk, k.check]), o.avoid ? [o.avoid.why] : [])), allowedFor(c, S.R, f, o));
    const evs = (o.candidates || []).concat(o.avoid ? [o.avoid] : []).map(k => C.checkEvidence(k.evidence, Object.assign({}, f, C.subFacts(c, S.R, k.code) || {})));
    const evAll = evs.flat(), evBad = evAll.filter(e => !e.ok).length;
    const head = evAll.length ? (evBad ? `<div class="numbad">⚠ 근거 숫자 ${evAll.length}개 중 ${evBad}개가 집계값과 다름 — 사람이 확인 필요</div>` : `<div class="numok">✓ 근거 숫자 ${evAll.length}개를 항목별로 집계값과 대조 — 모두 일치</div>`) : '';
    $('#recOut').innerHTML = badge(res) + head + numBadge(chk) + promptBox(recKey()) + `<p style="font-size:1.02rem;margin:.5rem 0"><b>${esc(o.summary)}</b></p>` +
      (o.candidates || []).map((k, i) => {
        const sf = C.subFacts(c, S.R, k.code), pk = `prep:${pid(c)}:${S.R}:${k.code}`;
        return `<div class="rec"><h4>${i + 1}. ${esc(k.biz)}</h4>
        <p><span class="k">이유</span>${esc(k.why)}</p><p><span class="k">위험</span>${esc(k.risk)}</p><p><span class="k">현장 확인</span>${esc(k.check)}</p>
        ${sf ? `<p class="note">자동 집계 — 「${esc(sf.업종)}」 반경 안 ${sf.반경안_지금}곳 · 비슷한 업종(${esc(sf.비슷한업종묶음)}) ${sf.반경안_비슷한업종}곳 · 동성로 범위 ${sf.범위_지난분기} → ${sf.범위_지금}곳 (사라짐 ${sf.범위_사라짐}, 새로 ${sf.범위_새로생김})</p>` : `<p class="note">자동 집계 — 상가정보 업종 목록에 없는 새 업종이라 비교 자료 없음</p>`}
        ${evHTML(evs[i])}
        <button class="btn ghost pickItem" data-i="${i}">이 사업으로 개업 준비 → ③</button>${!live() && !hasPre(pk) ? ' <span class="note">(미리 만들어 둔 답 없음 — 실시간 때만)</span>' : ''}</div>`;
      }).join('') +
      (o.avoid ? `<div class="rec no"><h4>권하지 않음: ${esc(o.avoid.biz)}</h4><p>${esc(o.avoid.why)}</p>${evHTML(evs[evs.length - 1])}</div>` : '') +
      (live() ? '<button class="btn ghost" id="reRec">다시 생성</button>' : '');
    document.querySelectorAll('.pickItem').forEach(b => b.onclick = () => { S.item = o.candidates[+b.dataset.i]; S.entry = 'new'; go(2); });
    const rr = $('#reRec'); if (rr) rr.onclick = () => { delete S.rec[recKey()]; step2(); $('#askRec').click(); };
  }

  // ---------- ③ ----------
  function helpersHTML(hs) {
    return `<div class="helpers"><h3>AI가 추린 필요한 일손 = 전공 · 업무 분야</h3>${(hs || []).map(h =>
      `<div class="hp"><b>${esc(h.field)}</b> · ${esc(h.weeks || '')}<br>할 일: ${esc(h.task)}<br>결과물: ${esc(h.deliverable)}<br><span class="note">왜: ${esc(h.why)}</span></div>`).join('')}</div>`;
  }
  function step3() {
    const tabs = `<div class="row" style="margin-bottom:.8rem"><button class="btn ${S.entry === 'new' ? '' : 'ghost'}" data-e="new">(가) 예비 사장님 — 빈 점포에 열기</button><button class="btn ${S.entry === 'old' ? '' : 'ghost'}" data-e="old">(나) 기존 사장님 — 가게 고민 진단</button></div>`;
    if (S.entry === 'new') step3a(tabs); else step3b(tabs);
    document.querySelectorAll('[data-e]').forEach(b => b.onclick = () => { S.entry = b.dataset.e; ticket++; step3(); });
  }
  const prepKey = () => (S.cand && S.item) ? `prep:${pid(S.cand)}:${S.R}:${S.item.code}` : '';
  function step3a(tabs) {
    const c = S.cand;
    if (!c) { $('#main').innerHTML = tabs + `<div class="card"><h2>먼저 ①에서 빈 점포를 고르고 ②에서 사업 후보를 받으세요</h2><button class="btn" id="b">① 빈 점포 찾기로</button></div>`; $('#b').onclick = () => go(0); return; }
    const rec = S.rec[recKey()], opts = rec ? rec.out.candidates : [];
    if (S.item && !opts.some(o => o.biz === S.item.biz && o.code === S.item.code)) S.item = null;
    if (!S.item && opts.length) S.item = opts[0];
    const key = prepKey(), cache = key && S.prep[key] && S.prep[key].prof === S.prof ? S.prep[key] : null;
    $('#main').innerHTML = tabs + `<div class="grid g3">
      <div class="card"><h2>(가) 예비 사장님 입력</h2>
        <div class="field"><span>위치 <span class="tag real">실제 데이터</span></span><b>${esc(short(c.addr))}</b> · ${c.f ? c.f + '층' : '층 정보 없음'} · 직전 ${esc(c.s)} · 반경 ${S.R}m</div>
        <div class="field"><span>아이템 (②의 AI 후보에서 고름)</span>${opts.length ? `<select id="item">${opts.map((o, i) => `<option value="${i}" ${S.item && S.item.biz === o.biz ? 'selected' : ''}>${esc(o.biz)}</option>`).join('')}</select>` : '<div class="warn">이 점포 · 반경으로 ②에서 AI 사업 후보를 먼저 받으세요.</div>'}</div>
        <div class="field"><span>예비 사장님 정보 <span class="tag ex">가상 예시</span></span><textarea id="prof">${esc(S.prof)}</textarea></div>
        <button class="btn" id="askPrep" ${S.item ? '' : 'disabled'}>AI 개업 준비 목록 · 필요한 일손 받기</button>
        ${S.item && !live() && !hasPre(key) ? '<p class="note">이 조합은 미리 만들어 둔 답이 없어 실시간 연결 때만 나옵니다.</p>' : ''}
      </div>
      <div class="card ai"><h2>AI: 개업 준비 목록 · 고려사항 · 필요한 일손</h2><div id="prepOut"></div></div></div>`;
    if (opts.length) $('#item').onchange = e => { S.item = opts[+e.target.value]; step3(); };
    $('#prof').oninput = e => { S.prof = e.target.value; if (S.prep[key]) $('#prepOut').innerHTML = '<div class="warn">예비 사장님 정보가 바뀌었습니다 — 「받기」를 다시 누르세요.</div>'; };
    if (S.item) $('#askPrep').onclick = e => { e.target.disabled = true; askPrep(c, prepKey()); };
    if (cache) showPrep(cache);
  }
  async function askPrep(c, key) {
    const t = ticket, prof = S.prof, item = S.item;
    $('#prepOut').innerHTML = '<div class="spin">AI가 개업 준비 목록을 만들고 있습니다…</div>';
    const f = C.facts(c, S.R), sf = C.subFacts(c, S.R, item.code);
    const user = `빈 점포에 처음 가게를 열려는 예비 사장님을 돕는다.\n[계산값]\n점포: ${JSON.stringify(f.점포)}\n반경 ${S.R}m 영업 중 ${f.반경안_영업중_업소수}곳, 사라짐 ${f.반경안_사라진업소수}곳, 새로 생김 ${f.반경안_새로생긴업소수}곳\n고른 업종 수치: ${JSON.stringify(sf)}\n직전 업종(${c.s}) 수치: 반경 안 지금 ${f.직전업종_반경안_지금}곳, 동성로 범위 ${JSON.stringify(f.직전업종_동성로범위)}\n` +
      `[고른 아이템] ${item.biz} — 이유: ${item.why} / 위험: ${item.risk}\n[예비 사장님] ${prof}\n\n` +
      `할 일: (1) 개업까지 할 일을 시기별로 정리(계약 전 / 계약~준비 / 개업 직전). 인허가 · 신고는 일반적인 절차 이름만 쓰고 확실하지 않으면 「구청 확인」이라고 쓴다. (2) 이 자리 · 이 아이템 · 이 사장님에게 특히 고려할 점 3개. (3) 사장님 혼자 하기 어려운 일을 대학생 프로젝트로 맡길 수 있게 「필요한 일손 = 전공 · 업무 분야」 2~3개로 추린다(한 학기 안에 끝나는 일).\n` +
      `출력 JSON: {"summary":"한 줄","stages":[{"when":"시기","items":["할 일"]}],"consider":["고려할 점"],"helpers":[{"field":"전공 · 업무 분야","task":"학생이 할 일","deliverable":"결과물","weeks":"기간(예: 8주)","why":"왜 필요한지"}]}`;
    const res = await getAI(prof === EX.newOwner.profile ? key : null, COMMON, user, 'prep');
    if (res.src === 'none') { if (still(t, 2, '#prepOut')) $('#prepOut').innerHTML = noneMsg(res, prof === EX.newOwner.profile ? '이 점포 · 아이템 조합' : '고친 예비 사장님 정보'); const b = $('#askPrep'); if (b) b.disabled = false; return; }
    res.prof = prof; S.prep[key] = res;
    if (still(t, 2, '#prepOut') && prepKey() === key && S.prof === prof) showPrep(res);
  }
  function showPrep(res) {
    const o = res.out;
    $('#prepOut').innerHTML = badge(res) + `<p style="margin:.5rem 0"><b>${esc(o.summary)}</b></p><div class="two"><div>` +
      helpersHTML(o.helpers) + `<div class="row" style="margin-top:.7rem"><button class="btn" id="toS4">이 일손으로 학생 공고 쓰기 → ④</button></div></div><div>` +
      (o.stages || []).map(s => `<h3>${esc(s.when)}</h3><ul>${s.items.map(i => `<li>${esc(i)}</li>`).join('')}</ul>`).join('') +
      `<h3>이 자리 · 이 아이템에서 고려할 점</h3><ul>${(o.consider || []).map(i => `<li>${esc(i)}</li>`).join('')}</ul></div></div>`;
    $('#toS4').onclick = () => { S.postSrc = 'prep'; go(3); };
    const b = $('#askPrep'); if (b) b.disabled = false;
  }
  const DR = 150; // (나)는 반경 150m 고정
  const diagKey = () => `diag:${pid(S.diagPoint)}:${DR}`;
  function step3b(tabs) {
    const p = S.diagPoint, key = diagKey(), cache = S.diag[key] && S.diag[key].worry === S.worry ? S.diag[key] : null;
    $('#main').innerHTML = tabs + `<div class="grid g3">
      <div class="card"><h2>(나) 기존 사장님 입력 <span class="tag ex">가상 예시</span></h2>
        <div class="field"><span>가게 근처 (숫자 집계 기준점)</span><select id="dp">${featured.map(c => `<option value="${c.id}" ${c.id === p.id ? 'selected' : ''}>${esc(short(c.addr))} 부근</option>`).join('')}</select></div>
        <div class="field"><span>업종 · 운영 기간</span><b>${esc(EX.oldOwner.biz)}</b> · ${esc(EX.oldOwner.years)}</div>
        <div class="field"><span>사장님이 적은 가게 고민 (예시 글)</span><textarea id="worry" style="min-height:9rem">${esc(S.worry)}</textarea></div>
        <button class="btn" id="askDiag">AI 경영 과제 진단 · 필요한 일손 받기</button>
        ${!live() && !hasPre(key) ? '<p class="note">이 기준점 · 반경은 미리 만들어 둔 답이 없어 실시간 연결 때만 나옵니다.</p>' : ''}
        <p class="note">사장님은 「디자인 전공 2명」이 아니라 「손님이 줄었다」고 말합니다. AI가 그 말을 할 일과 전공으로 바꿉니다.</p>
      </div>
      <div class="card ai"><h2>AI: 경영 과제 진단 · 필요한 일손</h2><div id="diagOut"></div></div></div>`;
    $('#dp').onchange = e => { S.diagPoint = D.cands.find(c => c.id === e.target.value); ticket++; step3(); };
    $('#worry').oninput = e => { S.worry = e.target.value; if (S.diag[key]) $('#diagOut').innerHTML = '<div class="warn">고민 글이 바뀌었습니다 — 「진단 받기」를 다시 누르세요.</div>'; };
    $('#askDiag').onclick = e => { e.target.disabled = true; askDiag(p, diagKey()); };
    if (cache) showDiag(cache);
  }
  function diagFacts(p) {
    const f = C.facts(p, DR), sf = C.subFacts(p, DR, EX.oldOwner.biz);
    return { 반경미터: DR, 반경안_영업중_업소수: f.반경안_영업중_업소수, 반경안_사라진업소수: f.반경안_사라진업소수, 반경안_새로생긴업소수: f.반경안_새로생긴업소수, 같은업종: sf };
  }
  function diagPrompt(p, worry) {
    const df = diagFacts(p);
    return `동성로에서 이미 장사하는 사장님의 고민을 경영 과제로 진단한다.\n[계산값] ${JSON.stringify(df)}\n` +
      `[사장님] 업종 ${EX.oldOwner.biz}, 운영 ${EX.oldOwner.years}\n[사장님이 적은 고민] ${worry}\n\n` +
      `할 일: (1) 고민을 우선순위가 있는 경영 과제 3개로 바꾼다. 각 과제에 사장님 글 중 근거가 된 부분을 그대로 인용하고, 계산값에 근거가 있으면 적고 없으면 「자료 없음 — 사장님과 확인」. 사장님 느낌과 계산값이 다르면 그것도 쓴다. 반경과 동성로 범위의 숫자가 다른 방향이면 둘 다 쓴다. 한 분기 비교만 있으므로 추세를 단정하지 않는다. (2) 대학생 프로젝트로 맡길 「필요한 일손 = 전공 · 업무 분야」 2~3개.\n` +
      `data 에 쓴 숫자는 evidence 에 항목 이름(key, 예: 같은업종.반경안_지금 · 반경안_사라진업소수)과 값으로 모두 적는다.\n` +
      `출력 JSON: {"summary":"한 줄","issues":[{"rank":1,"issue":"과제","owner_said":"사장님 글 인용","data":"계산값 근거","first_step":"첫 단계","evidence":[{"key":"같은업종.반경안_지금","value":0}]}],"helpers":[{"field":"전공 · 업무 분야","task":"학생이 할 일","deliverable":"결과물","weeks":"기간","why":"왜 필요한지"}]}`;
  }
  async function askDiag(p, key) {
    const t = ticket, worry = S.worry;
    $('#diagOut').innerHTML = '<div class="spin">AI가 사장님 글을 과제로 바꾸고 있습니다…</div>';
    const user = diagPrompt(p, worry);
    S.lastPrompt[key] = user;
    const res = await getAI(worry === EX.oldOwner.worry ? key : null, COMMON, user, 'diag');
    if (res.src === 'none') { if (still(t, 2, '#diagOut')) $('#diagOut').innerHTML = noneMsg(res, worry === EX.oldOwner.worry ? '이 기준점 · 반경' : '고친 고민 글'); const b = $('#askDiag'); if (b) b.disabled = false; return; }
    res.worry = worry; S.diag[key] = res;
    if (still(t, 2, '#diagOut') && diagKey() === key && S.entry === 'old' && S.worry === worry) showDiag(res);
  }
  function showDiag(res) {
    const o = res.out, p = S.diagPoint;
    const allowed = C.numbersIn(diagFacts(p)); allowed.add(String(DR));
    (EX.oldOwner.years.match(/\d+/g) || []).forEach(n => allowed.add(n));
    (res.worry.match(/\d+/g) || []).forEach(n => allowed.add(n));
    const chk = C.checkNumbers((o.issues || []).map(i => i.data + ' ' + i.issue + ' ' + i.first_step).join(' ') + ' ' + o.summary, allowed);
    const df = diagFacts(p), evs = (o.issues || []).map(i => C.checkEvidence(i.evidence, df)), evAll = evs.flat(), evBad = evAll.filter(e => !e.ok).length;
    const head = evAll.length ? (evBad ? `<div class="numbad">⚠ 근거 숫자 ${evAll.length}개 중 ${evBad}개가 집계값과 다름 — 사람이 확인 필요</div>` : `<div class="numok">✓ 근거 숫자 ${evAll.length}개를 항목별로 집계값과 대조 — 모두 일치</div>`) : '';
    if (!S.lastPrompt[diagKey()]) S.lastPrompt[diagKey()] = diagPrompt(p, res.worry);
    $('#diagOut').innerHTML = badge(res) + head + numBadge(chk) + promptBox(diagKey()) + `<p style="margin:.5rem 0"><b>${esc(o.summary)}</b></p><div class="two"><div>` +
      (o.issues || []).map((i, n) => `<div class="rec"><h4>${i.rank}. ${esc(i.issue)}</h4><p><span class="k">사장님 글(예시)</span>「${esc(i.owner_said)}」</p><p><span class="k">자료</span>${esc(i.data)}</p><p><span class="k">첫 단계</span>${esc(i.first_step)}</p>${evHTML(evs[n])}</div>`).join('') + '</div><div>' +
      helpersHTML(o.helpers) + `<div class="row" style="margin-top:.7rem"><button class="btn" id="toS4">이 일손으로 학생 공고 쓰기 → ④</button></div></div></div>`;
    $('#toS4').onclick = () => { S.postSrc = 'diag'; go(3); };
    const b = $('#askDiag'); if (b) b.disabled = false;
  }

  // ---------- ④ ----------
  const hash = o => { const t = JSON.stringify(o); let h = 0; for (let i = 0; i < t.length; i++) h = (h * 31 + t.charCodeAt(i)) | 0; return (h >>> 0).toString(36); };
  function currentSource() {
    let k, r, kind;
    if (S.postSrc === 'diag') { k = diagKey(); r = S.diag[k]; kind = 'diag'; if (!r || r.worry !== S.worry) return null; }
    else { k = prepKey(); r = k && S.prep[k]; kind = 'prep'; if (!r || r.prof !== S.prof) return null; }
    return { key: 'post:' + k + ':' + hash(r.out), pre: r.src === 'pre' ? 'post:' + k : null, kind, res: r };
  }
  const SKILLS = [...new Set(EX.students.concat([EX.applicant]).flatMap(s => s.skills))];
  function boardHTML() {
    return `<div class="board">${S.approved.map(p => `<div class="item"><b>${esc(p.out.title)}</b> <span class="tag ex">가상 예시</span> <span class="status ok">사장님 승인 · 게시됨</span><small>필요 전공: ${esc((p.out.majors || []).join(', '))} · ${esc(p.out.weeks)}주 · 주 ${esc(p.out.hours_per_week)}시간</small></div>`).join('')}
      ${EX.board.map(p => `<div class="item"><b>${esc(p.title)}</b> <span class="tag ex">가상 예시</span> <span class="status ${p.status === '모집 중' ? 'wait' : 'ok'}">${esc(p.status)}</span><small>필요 전공: ${esc(p.majors.join(', '))} · ${esc(p.period)}</small></div>`).join('')}</div>`;
  }
  function step4() {
    const src = currentSource();
    if (!src) { $('#main').innerHTML = `<div class="grid g5"><div class="card"><h2>먼저 ③에서 AI 진단 · 필요한 일손을 받으세요</h2><button class="btn" id="b">③으로</button></div><div class="card"><h2>학생 과제 게시판</h2>${boardHTML()}</div></div>`; $('#b').onclick = () => go(2); return; }
    const isOk = S.approved.some(p => p.key === src.key);
    $('#main').innerHTML = `<div class="grid g5">
      <div class="card ai"><h2>AI가 쓴 학생용 과제 공고문 <span class="tag ex">가상 가게</span></h2><div id="postOut"></div></div>
      <div class="card"><h2>학생 과제 게시판</h2><p class="note">사장님이 승인한 공고만 게시됩니다.</p>${boardHTML()}
        ${isOk ? '<button class="btn" id="toS5" style="margin-top:.6rem">학생 지원 · 매칭 보기 → ⑤</button>' : ''}</div></div>`;
    if ($('#toS5')) $('#toS5').onclick = () => { S.postSel = src.key; go(4); };
    if (S.post[src.key]) showPost(src); else askPost(src, '');
  }
  async function askPost(src, revise) {
    const t = ticket;
    $('#postOut').innerHTML = '<div class="spin">AI가 공고문을 쓰고 있습니다…</div>';
    const o = src.res.out;
    const where = src.kind === 'prep' ? `동성로 빈 점포(${short(S.cand.addr)} 부근)에 「${S.item.biz}」 개업을 준비하는 예비 사장님` : `동성로(${short(S.diagPoint.addr)} 부근)에서 ${EX.oldOwner.years}째 ${EX.oldOwner.biz} 가게를 하는 사장님`;
    const user = `대학생이 지원할 과제 공고문을 쓴다.\n[가게] ${where}\n[AI 진단 요약] ${o.summary}\n[필요한 일손] ${JSON.stringify(o.helpers)}\n[학생 역량 이름 목록] ${SKILLS.join(', ')}\n${revise ? '[사장님 수정 요청] ' + revise + '\n' : ''}\n` +
      `할 일: 학생이 읽고 바로 지원을 정할 수 있게 쓴다. 가게 이름 · 사장님 개인정보는 쓰지 않는다. 활동비 · 학점은 금액을 만들지 말고 「시범 사업 운영 기준에 따름」. 한 학기(16주) 안의 과제.\n` +
      `출력 JSON: {"title":"공고 제목","store":"가게 소개 1~2문장","why":"이 과제가 필요한 이유","tasks":["학생이 할 일"],"majors":["전공 이름 짧게(예: 시각디자인, 경영)"],"keywords":["역량 이름 목록에서 고른 것 3~6개"],"weeks":16,"hours_per_week":8,"deliverables":["결과물"],"qualify":["지원 조건"],"support":"활동비 · 학점: 시범 사업 운영 기준에 따름"}`;
    const usePre = !revise && (src.res.src === 'pre' || !live());
    const res = await getAI(usePre ? src.pre : null, COMMON, user, 'post');
    if (res.src === 'none') { if (still(t, 3, '#postOut')) $('#postOut').innerHTML = noneMsg(res, revise ? '수정 요청 반영' : '이 공고'); return; }
    S.post[src.key] = res;
    if (still(t, 3, '#postOut')) showPost(src);
  }
  function showPost(src) {
    const res = S.post[src.key], o = res.out, isOk = S.approved.some(p => p.key === src.key);
    $('#postOut').innerHTML = badge(res) + `<div class="post" style="margin-top:.6rem"><h3>${esc(o.title)}</h3>
      <p style="margin:.2rem 0">${esc(o.store)}</p><p style="margin:.2rem 0" class="note">${esc(o.why)}</p>
      <dl><dt>할 일</dt><dd><ul>${(o.tasks || []).map(t => `<li>${esc(t)}</li>`).join('')}</ul></dd>
      <dt>필요 전공</dt><dd>${(o.majors || []).map(m => `<span class="tag">${esc(m)}</span>`).join('')}</dd>
      <dt>필요 역량</dt><dd>${(o.keywords || []).map(m => `<span class="tag">${esc(m)}</span>`).join('')}</dd>
      <dt>기간 · 시간</dt><dd>${esc(o.weeks)}주 · 주 ${esc(o.hours_per_week)}시간</dd>
      <dt>결과물</dt><dd>${esc((o.deliverables || []).join(' / '))}</dd>
      <dt>지원 조건</dt><dd>${esc((o.qualify || []).join(' / '))}</dd>
      <dt>지원</dt><dd>${esc(o.support)}</dd></dl></div>
      ${isOk ? '<div class="done">✓ 사장님 승인 완료 — 게시판에 올라갔습니다 (가상 예시)</div>' :
        `<div class="row" style="margin-top:.7rem"><button class="btn ok" id="approve">사장님 승인하고 게시</button><button class="btn ghost" id="revBtn">고쳐 달라고 하기</button></div>
        <div id="revBox" class="hidden"><textarea id="rev" placeholder="예: 주말에는 가게가 바빠서 평일 오후에만 만날 수 있어요"></textarea><button class="btn" id="revGo">AI에게 다시 쓰게 하기</button></div>`}`;
    if (!isOk) {
      $('#approve').onclick = () => { S.approved.unshift({ key: src.key, out: o, pre: res.src === 'pre' ? src.pre : null }); step4(); };
      $('#revBtn').onclick = () => $('#revBox').classList.toggle('hidden');
      $('#revGo').onclick = () => {
        const rv = $('#rev').value.trim(); if (!rv) return;
        if (!live()) { $('#revBox').insertAdjacentHTML('beforeend', '<div class="warn">수정 요청 반영은 실시간 AI 연결 때만 됩니다.</div>'); return; }
        delete S.post[src.key]; askPost(src, rv);
      };
    }
  }

  // ---------- ⑤ ----------
  function step5() {
    if (!S.approved.length) { $('#main').innerHTML = `<div class="card"><h2>먼저 ④에서 사장님이 승인한 공고가 있어야 합니다</h2><button class="btn" id="b">④로</button></div>`; $('#b').onclick = () => go(3); return; }
    if (!S.postSel || !S.approved.some(p => p.key === S.postSel)) S.postSel = S.approved[0].key;
    const post = S.approved.find(p => p.key === S.postSel).out;
    const scored = S.students.map(s => ({ s, sc: C.matchScore(s, post) })).sort((a, b) => b.sc.total - a.sc.total);
    const hasApp = S.students.some(s => s.id === EX.applicant.id);
    const ap = S.approved.find(p => p.key === S.postSel);
    const mk = `match:${S.postSel}`, preMk = ap.pre ? 'match:' + ap.pre : null, sig = S.students.map(s => s.id).join(',');
    const cached = S.match[mk] && S.match[mk].sig === sig;
    $('#main').innerHTML = `<div class="grid g5">
      <div class="card"><h2>학생 지원 <span class="tag ex">가상 학생</span></h2>
        <div class="field"><span>공고</span><select id="ps" title="${esc(post.title)}">${S.approved.map(p => `<option value="${esc(p.key)}" ${p.key === S.postSel ? 'selected' : ''}>${esc(p.out.title)}</option>`).join('')}</select></div>
        <h3>새 지원서</h3>
        <div class="field"><span>이름(가명) · 전공</span><div class="row"><input type="text" id="an" style="flex:1" placeholder="학생 I"><input type="text" id="am" style="flex:1.4" placeholder="광고홍보학과"></div></div>
        <div class="field"><span>역량 (쉼표로)</span><input type="text" id="as" placeholder="인스타그램 운영, 브랜드 디자인"></div>
        <div class="field"><span>주당 가능 시간 · 요일</span><div class="row"><input type="number" id="ah" style="flex:.6" placeholder="12"><input type="text" id="aw" style="flex:1.4" placeholder="평일 오후 · 주말"></div></div>
        <div class="row"><button class="btn ghost" id="fill" ${hasApp ? 'disabled' : ''}>예시로 채우기</button><button class="btn" id="apply">지원하기</button></div><div id="applyMsg"></div>
        <h3>지원한 학생 ${S.students.length}명 — 규칙 점수</h3>
        <p class="note">전공 일치 40 + 역량 겹침(1개 10, 최대 40) + 가능 시간 20 = 100점</p>
        ${scored.map(({ s, sc }, i) => `<div class="st"><div><b>${i + 1}. ${esc(s.name)}</b> · ${esc(s.major)} ${s.year}학년 · 주 ${s.hours}시간(${esc(s.when)})<br><small>${esc(s.skills.join(', '))}${sc.hit.length ? ' — 겹침: ' + esc(sc.hit.join(', ')) : ''}</small></div><div class="score">${sc.total}점<br><small>${sc.major}+${sc.skill}+${sc.time}</small></div></div>`).join('')}
      </div>
      <div class="card ai"><h2>AI 매칭 후보 추천 → 담당자 최종 확인</h2>
        <p class="note">규칙 점수는 전공 · 낱말이 맞는지만 봅니다. AI는 공고의 할 일 · 결과물과 학생 역량 · 시간을 같이 읽고 후보 3명과 이유를 냅니다. 결정은 담당자가 합니다.</p>
        <div id="mOut">${cached ? '' : '<button class="btn" id="askM">AI 매칭 후보 추천 받기</button>'}</div></div></div>`;
    $('#ps').onchange = e => { S.postSel = e.target.value; ticket++; step5(); };
    $('#fill').onclick = () => { const a = EX.applicant; $('#an').value = a.name; $('#am').value = a.major; $('#as').value = a.skills.join(', '); $('#ah').value = a.hours; $('#aw').value = a.when; };
    $('#apply').onclick = () => {
      const name = $('#an').value.trim(), major = $('#am').value.trim(), skills = $('#as').value.split(',').map(x => x.trim()).filter(Boolean);
      if (!name || !major || !skills.length) { $('#applyMsg').innerHTML = '<p class="numbad">이름 · 전공 · 역량을 채워 주세요.</p>'; return; }
      if (S.students.some(s => s.name === name)) { $('#applyMsg').innerHTML = '<p class="numbad">같은 이름의 지원자가 이미 있습니다.</p>'; return; }
      const a = EX.applicant, isEx = name === a.name && major === a.major;
      S.students.push({ id: isEx ? a.id : 'U' + (S.students.length + 1), name, major, year: isEx ? a.year : 3, skills, hours: Math.max(0, Math.min(40, +$('#ah').value || 0)), when: $('#aw').value.trim() || '-' });
      delete S.confirmed[mk]; ticket++; step5();
    };
    if (cached) showMatch(mk, scored); else $('#askM').onclick = e => { e.target.disabled = true; askMatch(mk, preMk, post, scored, sig); };
  }
  async function askMatch(mk, preMk, post, scored, sig) {
    const t = ticket;
    $('#mOut').innerHTML = '<div class="spin">AI가 지원서를 읽고 있습니다…</div>';
    const user = `과제 공고와 지원한 학생을 보고 매칭 후보 3명을 추천한다.\n[공고] ${JSON.stringify(post)}\n[지원 학생 + 규칙 점수] ${JSON.stringify(scored.map(x => Object.assign({}, x.s, { rule_score: x.sc.total })))}\n\n` +
      `할 일: 점수만 따르지 말고 공고의 할 일 · 결과물과 학생 역량 · 시간을 같이 본다. 세 명이 서로 다른 일을 맡아 한 팀이 되게 고른다. 각 후보에 이유, 아쉬운 점, 첫 만남에서 확인할 것을 쓴다. 학생 id는 목록의 id를 그대로 쓴다.\n` +
      `출력 JSON: {"ranked":[{"id":"S01","why":"이유","gap":"아쉬운 점","ask":"첫 만남에서 확인할 것"}],"note":"담당자에게 한 줄"}`;
    const custom = S.students.filter(s => s.id.startsWith('U'));
    const res = await getAI(preMk, COMMON, user, 'match');
    if (res.src === 'none') { if (still(t, 4, '#mOut')) $('#mOut').innerHTML = noneMsg(res, '이 공고'); return; }
    // 미리 생성 결과는 예시 학생 9명 기준 순위 — 지금 지원한 학생만 남긴다
    if (res.src === 'pre') res.out = Object.assign({}, res.out, { ranked: res.out.ranked.filter(r => S.students.some(s => s.id === r.id)).slice(0, 3) });
    res.custom = res.src === 'pre' && custom.length ? custom.map(s => s.name) : [];
    res.sig = sig; S.match[mk] = res;
    if (still(t, 4, '#mOut') && mk === 'match:' + S.postSel && sig === S.students.map(s => s.id).join(',')) showMatch(mk, scored);
  }
  function showMatch(mk, scored) {
    const res = S.match[mk], o = res.out, conf = S.confirmed[mk];
    $('#mOut').innerHTML = badge(res) + (res.custom && res.custom.length ? `<p class="note">직접 입력한 지원자(${esc(res.custom.join(', '))})는 미리 만들어 둔 답에 없어 규칙 점수로만 보입니다. 실시간 연결 때는 AI가 함께 봅니다.</p>` : '') +
      (o.ranked || []).map((r, i) => {
        const s = S.students.find(x => x.id === r.id) || { name: r.id, major: '?' };
        const ri = scored.findIndex(x => x.s.id === r.id), sc = scored[ri];
        const moved = ri >= 0 && ri !== i ? `<span class="tag pre">규칙 점수 ${ri + 1}위 → AI ${i + 1}위</span>` : `<span class="tag">규칙 점수 ${ri + 1}위와 같음</span>`;
        return `<div class="rec"><h4>${i + 1}. ${esc(s.name)} · ${esc(s.major)} <span class="note">(규칙 점수 ${sc ? sc.sc.total : '-'}점)</span> ${moved}</h4>
        <p><span class="k">이유</span>${esc(r.why)}</p><p><span class="k">아쉬운 점</span>${esc(r.gap)}</p><p><span class="k">첫 만남 확인</span>${esc(r.ask)}</p>
        ${conf ? (conf === r.id ? '<div class="done">✓ 담당자 최종 확인 — 매칭 확정 (가상 예시)</div>' : '') : `<button class="btn ok conf" data-id="${esc(r.id)}">담당자 최종 확인</button>`}</div>`;
      }).join('') + (o.note ? `<p class="note">${esc(o.note)}</p>` : '') +
      (conf ? `<div class="done">매칭 확정: 사장님 · 학생에게 첫 만남 안내 (시연에서는 실제로 보내지 않음)<br>→ 한 학기 프로젝트 → 결과물 인수인계 · 성과 기록</div>` : '');
    document.querySelectorAll('.conf').forEach(b => b.onclick = () => { S.confirmed[mk] = b.dataset.id; showMatch(mk, scored); });
  }

  // ---------- 설정 ----------
  $('#modeBtn').onclick = () => { $('#apiKey').value = localStorage.getItem('dsr_key') || ''; $('#model').value = modelName(); $('#keyTest').textContent = ''; $('#modal').classList.add('show'); };
  $('#closeModal').onclick = () => $('#modal').classList.remove('show');
  $('#saveKey').onclick = async () => {
    const k = $('#apiKey').value.trim(); if (!k) return;
    $('#keyTest').textContent = '키 확인 중…';
    try {
      const r = await callClaude('', '「확인」 한 낱말로만 답하세요.', 200, 20000, k, $('#model').value);
      localStorage.setItem('dsr_key', k); localStorage.setItem('dsr_model', $('#model').value); setMode();
      $('#keyTest').textContent = '연결 확인됨 (' + r.model + '). 이제 AI 답을 실시간으로 생성합니다. 시연이 끝나면 「키 지우기」를 누르세요.';
    } catch (e) { $('#keyTest').textContent = '연결 실패: ' + (e.message || e) + ' — 키를 저장하지 않았습니다. 미리 만들어 둔 답을 계속 씁니다.'; }
  };
  $('#clearKey').onclick = () => { localStorage.removeItem('dsr_key'); setMode(); $('#modal').classList.remove('show'); };
  window.__S = S; // 점검용
  setMode(); history.replaceState({ s: 0 }, '', '#1'); go(0, true);
  // 시연 추천 점포에 미리 만들어 둔 답이 다 있는지 점검
  const miss = featured.length < 3 ? ['시연 추천 점포를 데이터에서 못 찾음'] : featured.filter(c => !hasPre(`rec:${pid(c)}:150`)).map(c => short(c.addr));
  if (miss.length) document.body.insertAdjacentHTML('afterbegin', `<div class="warn" style="margin:0;border-radius:0">점검: 미리 만들어 둔 답이 빠진 시연 점포 — ${esc(miss.join(', '))} (pregen.js 열쇠 확인)</div>`);
})();
