"""Compare a startup with public local data and produce preparation steps."""

from collections import defaultdict
from datetime import date
import json
from pathlib import Path

from compare_market import compare_markets, read_array, read_object, read_sites, read_text
from risk_assessment import format_risk_summary
from train_survival import read_date


PUBLIC_DATA_FORMAT = "dongseongro-startup-public-v1"
DEFAULT_PUBLIC_DATA = Path(__file__).with_name("dongseongro_startup_public.json")
INDUSTRY_ALIASES = {"까페": "카페", "커피숍": "카페", "커피전문점": "카페"}
PREPARATION_STAGES = (
    ("before_contract", "계약 조건 검토", ("monthly_cost", "deposit", "space")),
    ("validate_demand", "상품·수요 검증", ("competition", "permit_activity", "traffic", "differentiation", "idea")),
    ("before_opening", "개점 준비", ("marketing",)),
)
STUDENT_PROJECTS = (
    ("finance", ("monthly_cost", "deposit"), ("경영학", "회계학"), "임대 조건과 월 비용을 비교",
     "점포별 보증금·월세·관리비 및 예산 초과액 비교표"),
    ("space", ("space",), ("건축학", "실내건축디자인"), "운영에 필요한 공간 조건을 조사",
     "급배수·환기·출입 동선 체크리스트와 공간 배치 초안"),
    ("market", ("competition", "permit_activity", "traffic"), ("통계학", "데이터사이언스"), "경쟁점과 시간대별 고객 흐름을 조사",
     "반경 300m 경쟁 지도와 같은 시간대 보행량 조사표"),
    ("concept", ("differentiation", "idea"), ("경영학", "산업디자인"), "후보 아이템의 차별안을 검증",
     "경쟁점 비교표와 고객 반응을 확인할 시제품 또는 서비스 테스트 결과"),
    ("marketing", ("marketing",), ("광고홍보학", "시각디자인"), "고객 유입 채널을 설계",
     "지도 등록·SNS 콘텐츠 시안과 문의·예약 측정 계획"),
)


def load_public_data(path):
    data = read_object(json.loads(Path(path).read_text(encoding="utf-8-sig")), "공공데이터")
    if data.get("format") != PUBLIC_DATA_FORMAT or data.get("market_id") != "dongseongro":
        raise ValueError("동성로 창업 분석용 공공데이터 형식이 아닙니다.")
    as_of = read_date(data.get("as_of"), "공공데이터 기준일")
    market = read_object(data.get("market"), "공공데이터 상가 목록")
    transit = read_object(data.get("transit"), "공공데이터 교통 집계")
    permits = read_object(data.get("permits"), "공공데이터 인허가 집계")
    market_observed = read_date(market.get("observed_on"), "상가 관측일")
    market_published = read_date(market.get("published_on"), "상가 공개일")
    transit_published = read_date(transit.get("published_on"), "교통 공개일")
    permit_observed = read_date(permits.get("observed_on"), "인허가 확인일")
    if not market_observed <= market_published <= as_of or transit_published > as_of or permit_observed > as_of:
        raise ValueError("공공데이터 자료일과 공개일을 확인하세요.")
    if not read_sites(market.get("businesses"), "공공데이터 상가 목록"):
        raise ValueError("공공데이터 상가 목록이 비어 있습니다.")
    return data


def available(data, date_field, as_of):
    return data if data and date.fromisoformat(data[date_field]) <= as_of else None


def align_public_industries(candidates, businesses):
    by_code = {shop["industry_code"].casefold(): shop for shop in businesses if shop.get("industry_code")}
    by_label = defaultdict(dict)
    industry_counts = defaultdict(int)
    for shop in businesses:
        label = shop["industry"].casefold()
        code = shop.get("industry_code")
        by_label[label][code] = shop
        industry_counts[code or label] += 1

    normalized, matches = [], []
    for index, candidate in enumerate(candidates):
        source = read_object(candidate, f"candidates[{index}]")
        code = read_text(source.get("industry_code"), f"candidates[{index}].industry_code")
        label = read_text(source.get("industry"), f"candidates[{index}].industry")
        options = by_label.get(INDUSTRY_ALIASES.get(label, label).casefold()) if label else None
        shop = by_code.get(code.casefold()) if code else (
            next(iter(options.values())) if options and len(options) == 1 else None)
        match = {"status": "matched" if shop else "unmapped", "requested_industry": label,
                 "requested_code": code}
        if shop:
            matched_code = shop.get("industry_code")
            matched_label = shop["industry"]
            match.update(industry=shop["industry"], industry_code=shop.get("industry_code"),
                         industry_major=shop.get("industry_major"),
                         area_same_industry_count=industry_counts[matched_code or matched_label.casefold()])
        normalized.append({**source, "industry": match.get("industry"), "industry_code": match.get("industry_code")})
        matches.append(match)
    return normalized, matches


def public_comparison(row, match, market, radius):
    if market is None:
        return {"status": "unavailable", "radius_m": radius, "reason": "분석 기준일에 공개된 상가 자료를 확인해 주세요."}
    competitors = row["competition"]
    count = competitors["same_industry_count"]
    total = row["nearby_business_count"]
    area_count = match.get("area_same_industry_count")
    return {
        "status": "available", "radius_m": radius, "observed_on": market["observed_on"],
        "verified_through": market.get("verified_through"),
        "nearby_business_count": total, "industry_match": match, "same_industry_count": count,
        "same_industry_share_pct": round(100 * count / total, 2) if count is not None and total else None,
        "area_same_industry_share_pct": round(100 * area_count / len(market["businesses"]), 2)
        if area_count is not None else None,
        "nearest_competitors": competitors["competitors"][:5] if count is not None else [],
    }


def competition_preparation(public):
    if public["status"] == "unavailable":
        return {"key": "competition", "label": "주변 경쟁점", "status": "unknown", "status_label": "미확인",
                "evidence": public["reason"],
                "action": "비교 가능한 날짜의 상가 목록과 후보 점포 좌표를 준비하세요."}
    count = public["same_industry_count"]
    total = public["nearby_business_count"]
    radius = public["radius_m"]
    reference = f"{public['observed_on']} 공공 상가"
    if public["verified_through"]:
        reference += f"와 {public['verified_through']}까지 확인한 영업 이력"
    prefix = f"{reference} 기준 반경 {radius:g}m 내 업소 {total}곳"
    if not total:
        return {"key": "competition", "label": "주변 경쟁점", "status": "unknown", "status_label": "미확인",
                "evidence": prefix,
                "action": "입점 후보 좌표와 상가 자료가 포함하는 지번 범위를 확인하세요."}
    if count is None:
        return {"key": "competition", "label": "주변 경쟁점", "status": "unknown", "status_label": "미확인",
                "evidence": f"{prefix} · 희망 업종 코드 미확인",
                "action": "핵심 상품을 기준으로 소진공 상권업종 소분류를 확인하세요."}
    action = ("가까운 동일 업종 점포의 주력 상품·가격·고객층을 조사하고, 후보를 선택할 이유를 검증하세요."
              if count else "가까운 대체 업종과 목표 고객을 조사해 신규 아이템의 수요를 확인하세요.")
    evidence = (f"{prefix} · 동일 업종 {count}곳({public['same_industry_share_pct']:.1f}%) "
                f"· 동성로1·2·3가 전체 목록의 동일 업종 비중 {public['area_same_industry_share_pct']:.1f}%")
    return {"key": "competition", "label": "주변 경쟁점", "status": "verify", "status_label": "확인",
            "evidence": evidence, "action": action}


def permit_preparation(row, public, permits):
    industry = public.get("industry_match", {}).get("industry") or row["industry"]
    categories = permits["by_industry"]
    if industry in categories:
        counts = categories[industry]
        label = industry
    elif public.get("industry_match", {}).get("industry_major") == "음식":
        counts = permits
        label = "음식점 인허가 전체"
    else:
        return None
    evidence = (f"동성로1·2·3가 {label} · {permits['period_start']}~{permits['observed_on']} "
                f"신규 인허가 {counts['openings']}건, 폐업 기록 {counts['closures']}건 "
                f"({counts['closure_parcels']}개 지번)")
    if counts["closures"]:
        evidence += f" · 한 지번의 최대 폐업 기록 {counts['most_closures_at_one_parcel']}건"
    return {"key": "permit_activity", "label": "상권 내 인허가 변화", "status": "verify", "status_label": "확인",
            "evidence": evidence, "action": "비교 업소의 영업 기간과 같은 지번의 반복 신고를 확인해 경쟁 흐름을 살펴보세요."}


def startup_preparation(row, public, sources):
    assessment = row["risk_assessment"]
    risks = {check["key"]: check for check in assessment["checks"] if check["status"] != "clear"}
    risks["competition"] = competition_preparation(public)
    if sources["permits"]:
        permit_check = permit_preparation(row, public, sources["permits"])
        if permit_check:
            risks["permit_activity"] = permit_check
    transit = sources["transit"]
    if transit and "traffic" in risks and transit.get("weekend_to_weekday_ratio") is not None:
        traffic = risks["traffic"]
        ratio = transit["weekend_to_weekday_ratio"]
        risks["traffic"] = {**traffic, "evidence": (
            f"{traffic['evidence']} · {transit['observed_month']} 중앙로·반월당역 하차 기준 "
            f"주말 일평균은 평일의 {ratio:.1%}입니다.")}
    stages = []
    for key, label, items in PREPARATION_STAGES:
        checks = [risks[item] for item in items if item in risks]
        if checks:
            stages.append({"stage": key, "label": label, "items": sorted(
                checks, key=lambda item: {"high": 0, "attention": 1, "unknown": 2, "verify": 3}[item["status"]])})
    projects = []
    for key, linked_items, majors, task, deliverable in STUDENT_PROJECTS:
        relevant = [item for item in linked_items if item in risks]
        if relevant:
            subjects = list(majors)
            if key == "concept" and public.get("industry_match", {}).get("industry_major") == "음식":
                subjects.append("식품영양학")
            projects.append({"key": key, "related_checks": relevant, "suggested_majors": subjects,
                             "task": task, "deliverable": deliverable})
    return {"status": assessment["status"], "label": assessment["label"],
            "primary_reason": assessment["primary_reason"], "steps": stages, "student_projects": projects}


def analyze_startups(data, *, model=None, public_data=None, radius_m=None):
    payload = read_object(data, "요청")
    candidates = read_array(payload.get("candidates"), "candidates")
    known = public_data or {}
    as_of = read_date(payload.get("as_of"), "as_of")
    market = available(known.get("market"), "published_on", as_of)
    businesses = market["businesses"] if market is not None else []
    normalized, matches = align_public_industries(candidates, businesses)
    public_payload = {**payload, "candidates": normalized, "nearby_businesses": businesses,
                      "complete_market_inventory": False}

    if "nearby_businesses" in payload:
        report = compare_markets(payload, model=model, radius_m=radius_m)
        public_rows = (compare_markets(public_payload, radius_m=report["radius_m"])["comparisons"]
                       if market is not None else report["comparisons"])
        source = "backend_supplied"
    else:
        report = compare_markets(public_payload, model=model, radius_m=radius_m)
        public_rows = report["comparisons"]
        source = "public_catalog" if market is not None else "unavailable"

    report["comparison_source"] = source
    report["public_sources"] = {
        "area": known.get("area"),
        "market": {key: value for key, value in market.items() if key != "businesses"} if market else None,
        "transit": available(known.get("transit"), "published_on", as_of),
        "permits": available(known.get("permits"), "observed_on", as_of),
    }
    for row, public_row, match in zip(report["comparisons"], public_rows, matches):
        public = public_comparison(public_row, match, market, report["radius_m"])
        if source != "backend_supplied" and row["industry"] is None:
            row["industry"] = match["requested_industry"]
        row["public_comparison"] = public
        row["startup_preparation"] = startup_preparation(row, public, report["public_sources"])
    return report


def format_startup_summary(report):
    lines = [format_risk_summary(report)]
    for row in report["comparisons"]:
        preparation = row["startup_preparation"]
        lines.extend(("", f"{row['candidate_name']} | 창업 준비와 학생 과제"))
        for stage in preparation["steps"]:
            lines.append(f"  {stage['label']}")
            for item in stage["items"]:
                lines.append(f"    - {item['label']} · {item['evidence']}")
                lines.append(f"      {item['action']}")
        for project in preparation["student_projects"]:
            subjects = "·".join(project["suggested_majors"])
            lines.append(f"  학생 과제 | {subjects} | {project['task']} | 결과물 {project['deliverable']}")
    return "\n".join(lines)
