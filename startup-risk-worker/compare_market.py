"""Compare shortlisted businesses with nearby observations."""

import argparse
from collections import Counter, defaultdict
from dataclasses import dataclass
import json
import math
from pathlib import Path
from statistics import median
import sys

from risk_assessment import assess_risk, format_risk_summary
from survival_model import estimate_risk, load_model


@dataclass(frozen=True)
class Site:
    id: str
    name: str
    lat: float
    lon: float
    industry: str | None
    deposit_krw: int | None
    monthly_rent_krw: int | None
    monthly_management_krw: int | None
    floor: int | None
    area_m2: float | None
    space_features: tuple[str, ...] | None
    differentiators: tuple[str, ...] | None
    marketing_channels: tuple[str, ...] | None
    idea_summary: str | None
    idea_tags: tuple[str, ...] | None
    traffic_rates: dict[tuple[str, str], float]
    monthly_budget_krw: int | None = None
    deposit_budget_krw: int | None = None
    required_features: tuple[str, ...] | None = None
    unavailable_features: tuple[str, ...] | None = None
    industry_code: str | None = None

    @property
    def monthly_total_krw(self):
        if self.monthly_rent_krw is None or self.monthly_management_krw is None:
            return None
        return self.monthly_rent_krw + self.monthly_management_krw

    @property
    def monthly_total_krw_per_m2(self):
        if self.monthly_total_krw is None or self.area_m2 is None:
            return None
        return self.monthly_total_krw / self.area_m2


def read_object(value, path, *, optional=False):
    if value is None and optional:
        return {}
    if not isinstance(value, dict):
        raise ValueError(f"{path}에 JSON 객체가 필요합니다.")
    return value


def read_array(value, path):
    if not isinstance(value, list):
        raise ValueError(f"{path}에 JSON 배열이 필요합니다.")
    return value


def read_text(value, path, *, required=False):
    if value is None and not required:
        return None
    if isinstance(value, str) and value.strip():
        return value.strip()
    raise ValueError(f"{path}에 비어 있지 않은 문자열이 필요합니다.")


def read_number(value, path, *, required=False, integer=False, minimum=None, maximum=None):
    if value is None and not required:
        return None
    if type(value) not in (int, float) or not math.isfinite(value):
        raise ValueError(f"{path}에 유한한 숫자가 필요합니다.")
    if integer and type(value) is not int:
        raise ValueError(f"{path}에 정수가 필요합니다.")
    if (minimum is not None and value < minimum) or (maximum is not None and value > maximum):
        raise ValueError(f"{path}의 값이 허용 범위를 벗어났습니다.")
    return value


def read_tags(value, path):
    if value is None:
        return None
    tags = {}
    for index, raw_tag in enumerate(read_array(value, path)):
        tag = read_text(raw_tag, f"{path}[{index}]", required=True)
        tags.setdefault(tag.casefold(), tag)
    return tuple(tags.values())


def read_traffic_rates(value, path):
    if value is None:
        return {}
    totals = defaultdict(lambda: [0, 0])
    for index, entry in enumerate(read_array(value, path)):
        entry_path = f"{path}[{index}]"
        observation = read_object(entry, entry_path)
        period = read_text(observation.get("period"), f"{entry_path}.period", required=True)
        method = read_text(observation.get("method"), f"{entry_path}.method", required=True)
        count = read_number(observation.get("count"), f"{entry_path}.count",
                            required=True, integer=True, minimum=0)
        minutes = read_number(observation.get("minutes"), f"{entry_path}.minutes",
                              required=True, integer=True, minimum=1)
        totals[period, method][0] += count
        totals[period, method][1] += minutes
    return {key: 60 * count / minutes for key, (count, minutes) in totals.items()}


def read_site(value, path):
    row = read_object(value, path)
    location = read_object(row.get("location"), f"{path}.location")
    lease = read_object(row.get("lease"), f"{path}.lease", optional=True)
    space = read_object(row.get("space"), f"{path}.space", optional=True)
    idea = read_object(row.get("idea"), f"{path}.idea", optional=True)
    area = read_number(space.get("area_m2"), f"{path}.space.area_m2", minimum=0)
    if area == 0:
        raise ValueError(f"{path}.space.area_m2는 0보다 커야 합니다.")
    features = read_tags(space.get("features"), f"{path}.space.features")
    unavailable = read_tags(space.get("unavailable_features"), f"{path}.space.unavailable_features")
    if {tag.casefold() for tag in features or ()} & {tag.casefold() for tag in unavailable or ()}:
        raise ValueError(f"{path}.space에 있다고 확인한 조건과 없다고 확인한 조건이 겹칩니다.")

    return Site(
        id=read_text(row.get("id"), f"{path}.id", required=True),
        name=read_text(row.get("name"), f"{path}.name", required=True),
        lat=read_number(location.get("lat"), f"{path}.location.lat",
                        required=True, minimum=-90, maximum=90),
        lon=read_number(location.get("lon"), f"{path}.location.lon",
                        required=True, minimum=-180, maximum=180),
        industry=read_text(row.get("industry"), f"{path}.industry"),
        deposit_krw=read_number(lease.get("deposit_krw"), f"{path}.lease.deposit_krw",
                                integer=True, minimum=0),
        monthly_rent_krw=read_number(lease.get("monthly_rent_krw"),
                                     f"{path}.lease.monthly_rent_krw", integer=True, minimum=0),
        monthly_management_krw=read_number(lease.get("monthly_management_krw"),
                                           f"{path}.lease.monthly_management_krw",
                                           integer=True, minimum=0),
        floor=read_number(space.get("floor"), f"{path}.space.floor", integer=True),
        area_m2=area,
        space_features=features,
        differentiators=read_tags(row.get("differentiators"), f"{path}.differentiators"),
        marketing_channels=read_tags(row.get("marketing_channels"), f"{path}.marketing_channels"),
        idea_summary=read_text(idea.get("summary"), f"{path}.idea.summary"),
        idea_tags=read_tags(idea.get("tags"), f"{path}.idea.tags"),
        traffic_rates=read_traffic_rates(row.get("foot_traffic"), f"{path}.foot_traffic"),
        monthly_budget_krw=read_number(lease.get("monthly_budget_krw"), f"{path}.lease.monthly_budget_krw",
                                       integer=True, minimum=0),
        deposit_budget_krw=read_number(lease.get("deposit_budget_krw"), f"{path}.lease.deposit_budget_krw",
                                       integer=True, minimum=0),
        required_features=read_tags(space.get("required_features"), f"{path}.space.required_features"),
        unavailable_features=unavailable,
        industry_code=read_text(row.get("industry_code"), f"{path}.industry_code"),
    )


def read_sites(value, path):
    sites = [read_site(row, f"{path}[{index}]")
             for index, row in enumerate(read_array(value, path))]
    if len({site.id for site in sites}) != len(sites):
        raise ValueError(f"{path}에 중복된 점포 id가 있습니다.")
    return sites


def distance_m(first, second):
    lat1, lat2 = math.radians(first.lat), math.radians(second.lat)
    delta_lat = lat2 - lat1
    delta_lon = math.radians(second.lon - first.lon)
    square = (math.sin(delta_lat / 2) ** 2
              + math.cos(lat1) * math.cos(lat2) * math.sin(delta_lon / 2) ** 2)
    return 2 * 6_371_008.8 * math.asin(math.sqrt(min(1, square)))


def compare_numbers(candidate_value, peer_values):
    observed = [value for value in peer_values if value is not None]
    middle = median(observed) if observed else None
    difference = None if candidate_value is None or middle is None else candidate_value - middle
    return {
        "candidate": round(candidate_value, 2) if candidate_value is not None else None,
        "nearby_median": round(middle, 2) if middle is not None else None,
        "candidate_minus_median": round(difference, 2) if difference is not None else None,
        "sample_count": len(observed),
    }


def count_labels(labels):
    counts = Counter()
    display = {}
    for label in labels:
        key = label.casefold()
        counts[key] += 1
        display.setdefault(key, label)
    return counts, display


def compare_tags(candidate_tags, peer_tag_sets):
    observed = [tags for tags in peer_tag_sets if tags is not None]
    counts, display = count_labels(tag for tags in observed for tag in tags)
    return {
        "observed_peer_count": len(observed),
        "candidate_tags": None if candidate_tags is None else [
            {"tag": tag, "nearby_count": counts[tag.casefold()] if observed else None}
            for tag in candidate_tags
        ],
        "nearby_tags": [{"tag": display[key], "count": count}
                        for key, count in counts.most_common()],
    }


def compare_traffic(candidate, peers):
    peer_rates = defaultdict(list)
    for site in peers:
        for key, rate in site.traffic_rates.items():
            peer_rates[key].append(rate)
    return [
        {"period": period, "method": method, "people_per_hour": compare_numbers(
            candidate.traffic_rates.get((period, method)), peer_rates.get((period, method), ())
        )}
        for period, method in sorted(candidate.traffic_rates.keys() | peer_rates.keys())
    ]


def compare_market_composition(peers):
    counts, display = count_labels(site.industry for site in peers if site.industry is not None)
    known_count = sum(counts.values())
    return {
        "known_industry_count": known_count,
        "industries": [
            {"industry": display[key], "count": count,
             "share_pct_of_known": round(100 * count / known_count, 2)}
            for key, count in counts.most_common()
        ],
    }


def belongs_to_same_industry(candidate, business):
    if candidate.industry_code and business.industry_code:
        return candidate.industry_code.casefold() == business.industry_code.casefold()
    return (candidate.industry is not None and business.industry is not None
            and candidate.industry.casefold() == business.industry.casefold())


def compare_candidate(candidate, businesses, radius_m):
    nearby = []
    for business in businesses:
        if business.id == candidate.id:
            continue
        distance = distance_m(candidate, business)
        if distance <= radius_m:
            nearby.append((distance, business))
    nearby.sort(key=lambda match: (match[0], match[1].id))
    peers = [site for _, site in nearby]
    same_industry = [
        (distance, site) for distance, site in nearby
        if belongs_to_same_industry(candidate, site)
    ]
    competitors = [site for _, site in same_industry]
    same_floor = [site for site in peers if candidate.floor is not None and site.floor == candidate.floor]
    floor_counts = Counter(site.floor for site in peers if site.floor is not None)
    lease_metrics = (
        "deposit_krw", "monthly_rent_krw", "monthly_management_krw",
        "monthly_total_krw", "monthly_total_krw_per_m2",
    )

    comparison = {
        "candidate_id": candidate.id,
        "candidate_name": candidate.name,
        "industry": candidate.industry,
        "nearby_business_count": len(peers),
        "lease": {
            "comparable_floor": candidate.floor,
            "same_floor_peer_count": len(same_floor),
            **{metric: compare_numbers(getattr(candidate, metric),
                                      (getattr(site, metric) for site in same_floor))
               for metric in lease_metrics},
        },
        "competition": {
            "same_industry_count": len(competitors) if candidate.industry or candidate.industry_code else None,
            "competitors": [{"id": site.id, "name": site.name, "distance_m": round(distance, 1)}
                            for distance, site in same_industry],
            "differentiators": compare_tags(candidate.differentiators,
                                             (site.differentiators for site in competitors)),
        },
        "foot_traffic": compare_traffic(candidate, peers),
        "market": compare_market_composition(peers),
        "space": {
            "floor": candidate.floor,
            "nearby_floors": [{"floor": floor, "count": count}
                             for floor, count in sorted(floor_counts.items())],
            "area_m2": compare_numbers(candidate.area_m2, (site.area_m2 for site in same_floor)),
            "features": compare_tags(candidate.space_features, (site.space_features for site in peers)),
        },
        "marketing": compare_tags(candidate.marketing_channels,
                                  (site.marketing_channels for site in competitors)),
        "startup_idea": {
            "summary": candidate.idea_summary,
            **compare_tags(candidate.idea_tags, (site.idea_tags for site in competitors)),
        },
    }
    comparison["risk_assessment"] = assess_risk(candidate, comparison)
    return comparison


def compare_markets(data, *, radius_m=None, model=None):
    """Return JSON-ready comparisons for a set of candidates."""
    data = read_object(data, "입력")
    if radius_m is None:
        radius_m = model["radius_m"] if model is not None else 300
    radius_m = read_number(radius_m, "radius_m", required=True, minimum=0)
    if radius_m == 0:
        raise ValueError("radius_m은 0보다 커야 합니다.")
    if model is not None and radius_m != model["radius_m"]:
        raise ValueError("학습 모델과 비교 반경을 동일하게 지정하세요.")
    candidates = read_sites(data.get("candidates"), "candidates")
    businesses = read_sites(data.get("nearby_businesses"), "nearby_businesses")
    comparisons = [compare_candidate(candidate, businesses, radius_m) for candidate in candidates]
    if model is not None:
        market_id = read_text(data.get("market_id"), "market_id")
        for comparison in comparisons:
            comparison["learned_risk"] = estimate_risk(
                model, comparison, market_id=market_id, as_of=data.get("as_of"),
                complete_market_inventory=data.get("complete_market_inventory"),
            )
    return {
        "radius_m": radius_m,
        "comparisons": comparisons,
    }


def main():
    parser = argparse.ArgumentParser(description="후보 창업자와 주변 상권 자료 비교 및 생존 위험 진단")
    parser.add_argument("input", type=Path, help="입력 JSON 파일")
    parser.add_argument("--radius-m", type=float, help="비교 직선 반경(미터). 학습 모델을 쓰면 학습 반경이 기본값입니다.")
    parser.add_argument("--model", type=Path, help="검증을 마친 학습 모델 JSON")
    parser.add_argument("--startup", action="store_true", help="동성로 공공데이터에 기반한 창업 준비 계획 표시")
    parser.add_argument("--public-data", type=Path, help="창업 준비에 사용할 동성로 공공데이터 JSON")
    parser.add_argument("--summary", action="store_true", help="한국어 진단 요약 표시")
    parser.add_argument("--output", type=Path, help="결과를 저장할 UTF-8 파일")
    args = parser.parse_args()
    try:
        data = json.loads(args.input.read_text(encoding="utf-8-sig"))
        model = load_model(args.model) if args.model else None
        summary = format_risk_summary
        public_path = None
        if args.startup or args.public_data:
            from startup_analysis import DEFAULT_PUBLIC_DATA, analyze_startups, format_startup_summary, load_public_data

            public_path = args.public_data or DEFAULT_PUBLIC_DATA
            public_data = load_public_data(public_path)
            payload = {"market_id": "dongseongro", "as_of": public_data["as_of"], **read_object(data, "입력")}
            report = analyze_startups(payload, model=model, public_data=public_data, radius_m=args.radius_m)
            summary = format_startup_summary
        else:
            report = compare_markets(data, radius_m=args.radius_m, model=model)
        if args.summary:
            result = summary(report)
        else:
            result = json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False)
        if args.output is None:
            print(result)
        else:
            if args.output.resolve() in {path.resolve() for path in (args.input, args.model, public_path) if path is not None}:
                raise ValueError("입력·공공데이터·모델 파일과 다른 출력 경로를 지정하세요.")
            args.output.write_text(result + "\n", encoding="utf-8")
    except (OSError, ValueError) as error:
        print(f"오류: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    raise SystemExit(main())
