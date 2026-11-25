"""Train the 180-day closure model on dated, followed-up openings."""

import argparse
from collections import Counter
from datetime import date, timedelta
import json
from pathlib import Path
import sys
from tempfile import NamedTemporaryFile

from compare_market import compare_candidate, read_array, read_number, read_object, read_site, read_sites, read_text
from survival_model import HORIZON_DAYS, fit_model, industry_key, model_features


def read_date(value, field):
    try:
        result = date.fromisoformat(value)
    except (TypeError, ValueError) as error:
        raise ValueError(f"{field}에 YYYY-MM-DD 날짜가 필요합니다.") from error
    if result.isoformat() != value:
        raise ValueError(f"{field}에 YYYY-MM-DD 날짜가 필요합니다.")
    return result


def prepare_cases(data, radius_m):
    rows = []
    skipped = Counter()
    seen = set()
    as_of = read_date(data.get("outcomes_as_of"), "outcomes_as_of")
    for index, raw in enumerate(read_array(data.get("cases"), "cases")):
        path = f"cases[{index}]"
        case = read_object(raw, path)
        case_id = read_text(case.get("id"), f"{path}.id", required=True)
        if case_id in seen:
            raise ValueError(f"중복된 개업 이력 ID입니다. {case_id}")
        seen.add(case_id)
        opened = read_date(case.get("opened_on"), f"{path}.opened_on")
        snapshot = read_date(case.get("snapshot_on"), f"{path}.snapshot_on")
        observed = read_date(case.get("last_observed_on"), f"{path}.last_observed_on")
        closure = read_date(case["closed_on"], f"{path}.closed_on") if case.get("closed_on") is not None else None
        market = read_text(case.get("market_id"), f"{path}.market_id", required=True)
        property_id = read_text(case.get("property_id"), f"{path}.property_id", required=True)
        if not snapshot < opened <= observed <= as_of or (closure is not None and not opened <= closure <= observed):
            raise ValueError(f"{path}의 관측·개업·폐업 날짜 순서를 확인하세요.")

        horizon = opened + timedelta(days=HORIZON_DAYS)
        if horizon > as_of:
            skipped["cohort_not_mature"] += 1
            continue
        if closure is None and observed < horizon:
            skipped["followup_short"] += 1
            continue
        label = int(closure is not None and closure <= horizon)
        # This cutoff also handles late confirmation of a closure or ongoing business.
        known_on = case.get("outcome_recorded_on")
        label_available_on = read_date(known_on, f"{path}.outcome_recorded_on") if known_on is not None else observed
        event_or_window = closure if label else horizon
        if not event_or_window <= label_available_on <= as_of:
            raise ValueError(f"{path}의 결과 기록일을 확인하세요.")

        candidate = read_site(case.get("candidate"), f"{path}.candidate")
        nearby = read_sites(case.get("nearby_businesses"), f"{path}.nearby_businesses")
        comparison = compare_candidate(candidate, nearby, radius_m)
        features = model_features(comparison, complete_market_inventory=case.get("complete_market_inventory"))
        if features is None:
            skipped["market_inventory_unverified"] += 1
            continue
        rows.append({"id": case_id, "property_id": property_id, "market_id": market,
                     "industry": industry_key(candidate.industry), "opened_on": opened,
                     "label_available_on": max(horizon, label_available_on), "label": label, "features": features})
    return rows, skipped


def split_cases(rows, validation_start):
    days = sorted({row["opened_on"] for row in rows})
    if len(days) < 2:
        raise ValueError("날짜별 학습·검증에 쓸 수 있는 개업 이력이 부족합니다.")
    if validation_start is None:
        validation_start = days[min(len(days) - 1, int(0.75 * len(days)))]
    validation = [row for row in rows if row["opened_on"] >= validation_start]
    properties = {row["property_id"] for row in validation}
    training = []
    removed = Counter()
    for row in rows:
        if row["opened_on"] >= validation_start:
            continue
        if row["label_available_on"] >= validation_start:
            removed["outcome_not_known_at_split"] += 1
        elif row["property_id"] in properties:
            removed["property_in_validation"] += 1
        else:
            training.append(row)
    if any({row["label"] for row in partition} != {0, 1} for partition in (training, validation)):
        raise ValueError("시간과 건물로 분리한 학습·검증 집합 각각에 폐업 및 영업 유지 사례가 필요합니다.")
    return training, validation, validation_start, removed


def train(data, *, radius_m=300, validation_start=None):
    data = read_object(data, "학습 자료")
    radius_m = read_number(radius_m, "radius_m", required=True, minimum=1)
    data_origin = data.get("data_origin")
    outcome = data.get("outcome")
    if data_origin not in ("observed", "synthetic") or outcome not in ("actual_closure", "permit_closure"):
        raise ValueError("data_origin과 outcome을 학습 자료에 명시하세요.")
    rows, skipped = prepare_cases(data, radius_m)
    training, validation, cutoff, removed = split_cases(rows, validation_start)
    metadata = {"data_origin": data_origin, "outcome": outcome, "outcomes_as_of": data["outcomes_as_of"],
                "validation_start": cutoff.isoformat(), "training_count": len(training),
                "training_closures": sum(row["label"] for row in training),
                "excluded": dict(skipped + removed)}
    return fit_model(training, validation, radius_m=radius_m, metadata=metadata)


def save_model(path, model):
    content = json.dumps(model, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    with NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent, delete=False) as file:
        temporary = Path(file.name)
    try:
        temporary.write_text(content, encoding="utf-8")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description="상권 비교로 개업 후 180일 폐업 위험 모델 학습")
    parser.add_argument("input", type=Path, help="과거 개업 이력 학습 JSON")
    parser.add_argument("--output", type=Path, required=True, help="학습 결과를 저장할 모델 JSON")
    parser.add_argument("--radius-m", type=float, default=300, help="학습에 쓸 비교 반경")
    parser.add_argument("--validation-start", help="검증 개업 시작 날짜(YYYY-MM-DD)")
    args = parser.parse_args()
    try:
        if args.input.resolve() == args.output.resolve():
            raise ValueError("입력과 다른 모델 출력 경로를 지정하세요.")
        data = json.loads(args.input.read_text(encoding="utf-8-sig"))
        cutoff = read_date(args.validation_start, "validation_start") if args.validation_start else None
        model = train(data, radius_m=args.radius_m, validation_start=cutoff)
        save_model(args.output, model)
        print(json.dumps({"metadata": model["metadata"], "validation": model["validation"],
                          "scopes": model["scopes"]}, ensure_ascii=False, indent=2))
    except (OSError, TypeError, ValueError, OverflowError) as error:
        print(f"오류: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    raise SystemExit(main())
