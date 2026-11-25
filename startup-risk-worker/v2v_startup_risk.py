"""Translate direct or V2V inputs into a Dongseongro comparison."""

from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import re
import sys
from uuid import UUID

from compare_market import read_number, read_object, read_text
from startup_analysis import DEFAULT_PUBLIC_DATA, INDUSTRY_ALIASES, analyze_startups, available, load_public_data
from survival_model import load_model


DEFAULT_MODEL = Path(__file__).with_name("dongseongro_agent_model.json")
WORD = "가-힣a-z0-9"


def industry_from_idea(idea, businesses):
    labels = {shop["industry"] for shop in businesses}
    names = {label.casefold(): label for label in labels}
    names.update({alias: label for alias, label in INDUSTRY_ALIASES.items() if label in labels})
    text = idea.strip().casefold()
    if text in names:
        return names[text]
    matches = {
        label for name, label in names.items() if len(name) >= 2 and re.search(
            rf"(?<![{WORD}]){re.escape(name)}(?:에서|으로|을|를|이|가|은|는)?(?![{WORD}])", text
        )
    }
    return next(iter(matches)) if len(matches) == 1 else None


def in_observed_bounds(businesses, latitude, longitude):
    latitudes = [shop["location"]["lat"] for shop in businesses]
    longitudes = [shop["location"]["lon"] for shop in businesses]
    return min(latitudes) <= latitude <= max(latitudes) and min(longitudes) <= longitude <= max(longitudes)


def analyze_job(inputs, *, public_data, model, as_of):
    inputs = read_object(inputs, "V2V input")
    plan = read_object(inputs.get("plan"), "V2V input.plan")
    location = read_object(inputs.get("location"), "V2V input.location")
    vacancy = read_object(inputs.get("vacancy"), "V2V input.vacancy", optional=True)
    latitude, longitude = location.get("latitude"), location.get("longitude")

    idea = plan["businessIdea"]
    market = available(public_data["market"], "published_on", as_of)
    businesses = market["businesses"] if market else []
    if businesses and not in_observed_bounds(businesses, latitude, longitude):
        message = "동성로1·2·3가 시범 자료가 포함하는 좌표 범위에서 후보를 확인할 수 없습니다."
        return {
            "analysisStatus": "outside_coverage",
            "summary": message,
            "planId": plan["id"],
            "findings": [{"key": "coverage", "label": "시범 상권 범위", "status": "unknown",
                          "status_label": "미확인", "evidence": message, "action": "후보지 주소와 좌표를 확인하세요."}],
            "candidates": [],
            "sources": {"area": public_data["area"], "market": {"observed_on": market["observed_on"]}},
        }
    deposit = vacancy.get("depositKrw")
    founder_budget = inputs.get("founderBudgetKrw")
    exceeds_budget = deposit is not None and founder_budget is not None and deposit > founder_budget
    deposit_budget = inputs.get("depositBudgetKrw")
    if exceeds_budget and (deposit_budget is None or founder_budget < deposit_budget):
        deposit_budget = founder_budget
    explicit_industry = plan.get("industry")
    industry = explicit_industry or industry_from_idea(idea, businesses)
    candidate = {
        "id": str(vacancy.get("id") or plan["id"] or "direct-startup"),
        "name": idea,
        "industry": industry,
        "idea": {"summary": idea},
        "location": {"lat": latitude, "lon": longitude},
        "lease": {
            "deposit_krw": deposit,
            "deposit_budget_krw": deposit_budget,
            "monthly_rent_krw": vacancy.get("monthlyRentKrw"),
            "monthly_management_krw": vacancy.get("monthlyManagementKrw"),
            "monthly_budget_krw": inputs.get("monthlyBudgetKrw"),
        },
        "space": {"area_m2": vacancy.get("areaM2")},
    }
    report = analyze_startups(
        {"market_id": "dongseongro", "as_of": as_of.isoformat(), "candidates": [candidate]},
        model=model, public_data=public_data, radius_m=300,
    )
    sources = report["public_sources"]
    row = report["comparisons"][0]
    comparison = row["public_comparison"]
    risk = row["risk_assessment"]
    preparation = row["startup_preparation"]
    status = "analyzed" if comparison.get("industry_match", {}).get("status") == "matched" else "partial"
    return {
        "analysisStatus": status,
        "summary": f"{risk['label']} · {risk['primary_reason']}",
        "planId": plan["id"],
        "radiusM": report["radius_m"],
        "industryInferred": industry is not None and explicit_industry is None,
        "founderBudgetKrw": founder_budget,
        "findings": [item for stage in preparation["steps"] for item in stage["items"]],
        "candidates": [row],
        "studentProjects": preparation["student_projects"],
        "sources": {**sources, "areaStatistics": inputs.get("areaStatistics")},
    }


def analyze_direct(payload, *, public_data, model, as_of):
    payload = read_object(payload, "창업 입력")
    idea = read_text(payload.get("businessIdea"), "businessIdea", required=True)
    industry = read_text(payload.get("industry"), "industry")
    if len(idea) > 500 or (industry is not None and len(industry) > 160):
        raise ValueError("businessIdea는 500자, industry는 160자까지 입력할 수 있습니다.")
    plan_id = read_text(payload.get("planId"), "planId")
    if plan_id is not None:
        try:
            plan_id = str(UUID(plan_id))
        except ValueError as error:
            raise ValueError("planId는 V2V 창업 계획 ID 형식이어야 합니다.") from error
    latitude = read_number(payload.get("latitude"), "latitude", required=True, minimum=-90, maximum=90)
    longitude = read_number(payload.get("longitude"), "longitude", required=True, minimum=-180, maximum=180)
    amounts = {
        key: read_number(payload.get(key), key, integer=True, minimum=0, maximum=1_000_000_000_000_000)
        for key in ("founderBudgetKrw", "depositBudgetKrw", "monthlyBudgetKrw",
                    "depositKrw", "monthlyRentKrw", "monthlyManagementKrw")
    }
    area = read_number(payload.get("areaM2"), "areaM2", minimum=0.01)
    inputs = {
        "plan": {"id": plan_id, "businessIdea": idea, "industry": industry},
        "location": {"latitude": latitude, "longitude": longitude},
        "founderBudgetKrw": amounts["founderBudgetKrw"],
        "depositBudgetKrw": amounts["depositBudgetKrw"],
        "monthlyBudgetKrw": amounts["monthlyBudgetKrw"],
        "vacancy": {
            "depositKrw": amounts["depositKrw"], "monthlyRentKrw": amounts["monthlyRentKrw"],
            "monthlyManagementKrw": amounts["monthlyManagementKrw"], "areaM2": area,
        },
    }
    return analyze_job(inputs, public_data=public_data, model=model, as_of=as_of)


def main():
    as_of = datetime.now(timezone(timedelta(hours=9))).date()
    inputs = json.load(sys.stdin.buffer)
    result = analyze_job(inputs, public_data=load_public_data(DEFAULT_PUBLIC_DATA),
                         model=load_model(DEFAULT_MODEL), as_of=as_of)
    sys.stdout.buffer.write(json.dumps(result, ensure_ascii=False, allow_nan=False).encode("utf-8"))


if __name__ == "__main__":
    main()
