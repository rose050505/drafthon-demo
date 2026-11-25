"""상가(상권)정보 2026년 2분기(202606) · 3분기(202609) 대구 파일을 비교해
동성로 범위(중구 성내1동 + 도로명 '동성로')의 영업 중 업소와 '지난 분기에 있다가 사라진 업소'(빈 점포 후보)를 뽑는다.
반경 계산이 범위 경계에서 모자라지 않게, 범위 둘레 400m 안 업소도 함께 넣는다(지도에는 범위 안만 그림).
사용: python3 -I tools/build_data.py <202606.csv> <202609.csv>  -> data/data.js

「사라짐」 규칙 (같은 업소를 사라졌다고 세지 않게):
 1. 같은 업소번호가 3분기 대구 파일 어디에도 없을 것
 2. 같은 건물(건물관리번호)에 이름이 같거나 한쪽이 다른 쪽을 포함하는 3분기 업소가 있으면 사라짐이 아님
    - 그 업소가 3분기에 새 번호로 나왔으면 「번호 · 이름만 바뀐 같은 업소」(새로 생김에서도 뺌)
    - 원래 있던 번호로 남아 있으면 「중복 기록 정리」
 3. 사라진 업소끼리 같은 건물 · 같은 이름이면 하나만 남김(나머지는 중복 기록 정리)
"""
import csv, sys, json, re, math, difflib, collections as C
def load(f): return {r['상가업소번호']: r for r in csv.DictReader(open(f, encoding='utf-8'))}
q2, q3 = load(sys.argv[1]), load(sys.argv[2])
ins = lambda r: r['시군구명'] == '중구' and (r['행정동명'] == '성내1동' or '동성로' in r['도로명'])
ALIAS = [('세븐일레븐', '세븐')]
def norm(s):
    s = re.sub(r'[\s()\[\]\-·.,_/&]', '', s)
    for a, b in ALIAS: s = s.replace(a, b)
    return s
def same(a, b):
    """같은 업소로 볼 만큼 이름이 같은가: 3글자 이하는 완전 일치, 포함 관계는 대분류까지 같을 때, 같은 소분류면 이름 유사도 0.6 이상"""
    x, y = norm(a['상호명']), norm(b['상호명'])
    if len(x) < 2 or len(y) < 2: return False
    if x == y: return True
    if min(len(x), len(y)) <= 3: return False
    if (x in y or y in x) and a['상권업종대분류명'] == b['상권업종대분류명']: return True
    return a['상권업종소분류명'] == b['상권업종소분류명'] and difflib.SequenceMatcher(None, x, y).ratio() >= 0.6
LAT0 = math.radians(35.8695)
KY = 111132.92 - 559.82 * math.cos(2 * LAT0) + 1.175 * math.cos(4 * LAT0)   # 위도 1도 길이(m)
KX = 111412.84 * math.cos(LAT0) - 93.5 * math.cos(3 * LAT0)                 # 경도 1도 길이(m)
def m(r): return (float(r['경도']) * KX, float(r['위도']) * KY)

# 범위 + 둘레 400m 상자
sc2 = [r for r in q2.values() if ins(r)]
xs = [m(r)[0] for r in sc2]; ys = [m(r)[1] for r in sc2]
box = (min(xs) - 400, max(xs) + 400, min(ys) - 400, max(ys) + 400)
inbox = lambda r: r['경도'] and box[0] <= m(r)[0] <= box[1] and box[2] <= m(r)[1] <= box[3]

def classify(A2, A3):
    """A2, A3: {업소번호: 행} 비교 구역. q2 · q3 전체를 참고해 사라짐 · 새로 생김을 가른다."""
    by_bld = C.defaultdict(list)
    for r in q3.values(): by_bld[r['건물관리번호']].append(r)
    by_name = C.defaultdict(list)                     # 30m 안으로 옮긴 같은 이름
    for r in q3.values():
        if r['상가업소번호'] not in q2: by_name[norm(r['상호명'])].append(r)
    gone_id = sorted((r for k, r in A2.items() if k not in q3), key=lambda r: r['상가업소번호'])
    paired, kept, dup, gone = set(), [], [], []
    for g in gone_id:
        hit = [r for r in by_bld[g['건물관리번호']] if same(g, r)]
        hit += [r for r in by_name.get(norm(g['상호명']), []) if len(norm(g['상호명'])) >= 2 and r['경도'] and math.dist(m(r), m(g)) <= 30]
        if not hit: gone.append(g); continue
        nw = [r for r in hit if r['상가업소번호'] not in q2 and r['상가업소번호'] not in paired]
        if nw: paired.add(nw[0]['상가업소번호']); kept.append(g)
        else: dup.append(g)
    g2 = []
    for g in gone:                       # 사라진 것끼리 중복
        if any(g['건물관리번호'] == h['건물관리번호'] and same(g, h) for h in g2): dup.append(g)
        else: g2.append(g)
    new = [r for k, r in A3.items() if k not in q2 and k not in paired]
    return g2, kept, dup, new, paired

A2 = {k: r for k, r in q2.items() if inbox(r)}
A3 = {k: r for k, r in q3.items() if inbox(r)}
goneA, keptA, dupA, newA, pairedA = classify(A2, A3)
newA_ids = {r['상가업소번호'] for r in newA}

# 범위 안 숫자
s2 = {k: r for k, r in q2.items() if ins(r)}; s3 = {k: r for k, r in q3.items() if ins(r)}
gone = [r for r in goneA if ins(r)]; kept = [r for r in keptA if ins(r)]; dup = [r for r in dupA if ins(r)]
new = [r for r in newA if ins(r)]
assert len(s2) - len(gone) - len(dup) + len(new) == len(s3), '분기 업소 수가 맞지 않음'

# 대구 전체 비교(맥락용): 같은 규칙
gD, kD, dD, nD, _ = classify(q2, q3)

# 같은 건물 · 같은 층 새 업소 / 같은 건물 새 업소 / 근처로 옮김
new_by_bld = C.defaultdict(list)
for r in newA: new_by_bld[r['건물관리번호']].append(r)
new_by_name = C.defaultdict(list)
for r in q3.values():
    if r['상가업소번호'] not in q2: new_by_name[norm(r['상호명'])].append(r)
def short(r, full=True):
    d = dict(x=round(float(r['경도']), 6), y=round(float(r['위도']), 6), l=r['상권업종대분류명'], s=r['상권업종소분류명'])
    if full: d.update(m=r['상권업종중분류명'], f=r['층정보'], dsr=1 if '동성로' in r['도로명'] else 0)
    if ins(r): d['in'] = 1
    return d
active = []
for r in A3.values():
    d = short(r)
    if r['상가업소번호'] in newA_ids: d['n'] = 1
    active.append(d)
prev = [short(r, False) for r in A2.values()]
cands = []
for i, r in enumerate(sorted(goneA, key=lambda r: (not ins(r), r['도로명주소']))):
    d = short(r)
    bn = new_by_bld.get(r['건물관리번호'], [])
    sf = [x['상권업종소분류명'] for x in bn if r['층정보'] and x['층정보'] == r['층정보']]
    mv = [x for x in new_by_name.get(norm(r['상호명']), []) if len(norm(r['상호명'])) >= 2 and x['건물관리번호'] != r['건물관리번호']
          and math.dist(m(x), m(r)) <= 300]
    d.update(id=f"V{i+1:03d}", addr=r['도로명주소'].strip(), bld=r['건물명'], filled=sf, bnew=len(bn),
             moved=mv[0]['도로명주소'] if mv else '')
    cands.append(d)
inc = [c for c in cands if c.get('in')]
# 랜드마크: 건물명 · 업소 좌표 평균 (데이터에서 계산)
lm = {}
for name in ['대구스테이션센터', '교동시장', '영스퀘어', '대현프리몰', '동성로타워']:
    pts = [(float(r['경도']), float(r['위도'])) for r in s3.values() if r['건물명'] == name]
    if pts: lm[name] = [round(sum(p[0] for p in pts) / len(pts), 6), round(sum(p[1] for p in pts) / len(pts), 6)]
# 범위 전체 업종(소분류) 분기 변화: [2분기, 3분기, 사라짐, 새로, 중복정리]
c2 = C.Counter(r['상권업종소분류명'] for r in s2.values()); c3 = C.Counter(r['상권업종소분류명'] for r in s3.values())
cg = C.Counter(r['상권업종소분류명'] for r in gone); cn = C.Counter(r['상권업종소분류명'] for r in new)
cd = C.Counter(r['상권업종소분류명'] for r in dup)
# 번호 · 이름만 바뀐 짝 중 업종이 바뀐 것은 업종별 표에서 옮겨 감 -> 업종별 등식은 [이전 - 사라짐 - 중복 + 새로 + 업종 이동]
sub = {k: [c2[k], c3[k], cg[k], cn[k], c3[k] - (c2[k] - cg[k] + cn[k])] for k in set(c2) | set(c3)}   # 5번째 = 중복 정리 · 업종 분류 변경
meta = dict(src='소상공인시장진흥공단 상가(상권)정보 (공공데이터포털)',
            q_prev='2026년 2분기(2026-06 기준)', q_now='2026년 3분기(2026-09 기준)',
            scope='대구 중구 성내1동 + 도로명 「동성로」 구간',
            n_prev=len(s2), n_now=len(s3), n_gone=len(gone), n_new=len(new), n_kept=len(kept), n_dup=len(dup),
            n_floor1=sum(1 for c in inc if c['f'] == '1'),
            n_floor1_open=sum(1 for c in inc if c['f'] == '1' and not c['filled']),
            n_floor_unknown=sum(1 for c in inc if not c['f']),
            n_filled=sum(1 for c in inc if c['filled']),
            n_moved=sum(1 for c in inc if c['moved']),
            rate_scope=round(100 * len(gone) / len(s2), 1), rate_daegu=round(100 * len(gD) / len(q2), 1),
            n_daegu_prev=len(q2), n_daegu_gone=len(gD))
dups = [short(r, False) for r in dupA]
out = dict(meta=meta, active=active, prev=prev, cands=cands, dups=dups, lm=lm, sub=sub)
open('data/data.js', 'w', encoding='utf-8').write('window.DATA=' + json.dumps(out, ensure_ascii=False, separators=(',', ':')) + ';')
print(json.dumps(meta, ensure_ascii=False, indent=1)); print('구역 업소', len(A3), '후보(구역)', len(cands))
