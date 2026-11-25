"""Train and apply a fixed-horizon closure model using local market comparisons."""

from collections import Counter, defaultdict
from datetime import date
import json
import math
from pathlib import Path
from statistics import mean, median, pstdev


FORMAT = "market-closure-logistic-v1"
HORIZON_DAYS = 180
MIN_SCOPE_OUTCOMES_PER_CLASS = 5
DEFAULT_PENALTY = 0.02
FOOD_PERMIT_RESEARCH = "dongseongro_food_permit_reconstruction"
VERIFIED_STARTUP_STUDY = "dongseongro_verified_followup"
FEATURE_LABELS = {
    "competition_count": "동일 업종 수", "competition_share": "동일 업종 비중",
    "market_size": "주변 업소 수", "rent_ratio": "같은 층 대비 월 임대 부담",
    "traffic_ratio": "같은 시간대 대비 보행량", "differentiator_overlap": "경쟁점과 차별점 중복",
    "idea_overlap": "경쟁점과 아이템 태그 중복", "first_floor": "1층 여부", "area": "점포 면적",
    "recent_openings": "최근 1년 주변 음식점 인허가 수",
    "recent_closures": "최근 1년 주변 음식점 폐업 수",
    "recent_same_industry_closures": "최근 1년 같은 업종 폐업 수",
    "catalog_shop_count": "분기 상가정보의 주변 업소 수",
    "catalog_food_share": "분기 상가정보의 주변 음식업 비중",
    "catalog_cafe_count": "분기 상가정보의 주변 카페 수",
    "stations_daily_alightings": "인근 세 역 일평균 하차",
    "stations_weekend_ratio": "인근 세 역 평일 대비 주말 하차 비율",
    "monthly_budget_pressure": "예산 대비 월세·관리비",
    "deposit_budget_pressure": "예산 대비 보증금",
}


def industry_key(value):
    return value.strip().casefold() if isinstance(value, str) else ""


def relative_metric(metric):
    value = metric["candidate"]
    reference = metric["nearby_median"]
    if value is None or not reference or metric["sample_count"] < 2:
        return None
    return math.log1p(value) - math.log1p(reference)


def tag_overlap(comparison, competitor_count):
    tags = comparison["candidate_tags"]
    sample = comparison["observed_peer_count"]
    if not tags or sample < 2 or sample * 2 < competitor_count:
        return None
    return mean(item["nearby_count"] / sample for item in tags)


def model_features(comparison, *, complete_market_inventory):
    """Use exactly the same point-in-time features for training and prediction."""
    total = comparison["nearby_business_count"]
    known = comparison["market"]["known_industry_count"]
    direct = comparison["competition"]["same_industry_count"]
    if complete_market_inventory is not True or direct is None or known != total:
        return None

    traffic = [relative_metric(row["people_per_hour"]) for row in comparison["foot_traffic"]
               if row["people_per_hour"]["candidate"] is not None]
    area = comparison["space"]["area_m2"]["candidate"]
    floor = comparison["space"]["floor"]
    return {
        "competition_count": math.log1p(direct),
        "competition_share": direct / total if total else 0.0,
        "market_size": math.log1p(total),
        "rent_ratio": relative_metric(comparison["lease"]["monthly_total_krw_per_m2"]),
        "traffic_ratio": min(traffic) if traffic and None not in traffic else None,
        "differentiator_overlap": tag_overlap(comparison["competition"]["differentiators"], direct),
        "idea_overlap": tag_overlap(comparison["startup_idea"], direct),
        "first_floor": float(floor == 1) if floor is not None else None,
        "area": math.log1p(area) if area is not None else None,
    }


def fit_columns(rows):
    columns = {}
    for name in FEATURE_LABELS:
        observed = [row["features"][name] for row in rows if row["features"].get(name) is not None]
        if not observed:
            continue
        fill = median(observed)
        filled = [fill if row["features"].get(name) is None else row["features"][name] for row in rows]
        columns[name] = {"fill": fill, "mean": mean(filled), "scale": pstdev(filled) or 1.0}
    return columns


def coverage_key(features, columns):
    return "".join("1" if features.get(name) is not None else "0" for name in sorted(columns))


def vector(features, industry, columns, industries):
    values = []
    for name in sorted(columns):
        column = columns[name]
        value = features.get(name)
        filled = column["fill"] if value is None else value
        values.extend(((filled - column["mean"]) / column["scale"], float(value is None)))
    values.extend(float(industry == category) for category in industries)
    return values


def sigmoid(value):
    if value < 0:
        exponential = math.exp(value)
        return exponential / (1 + exponential)
    return 1 / (1 + math.exp(-value))


def fit_logistic(examples, *, penalty=DEFAULT_PENALTY):
    size = len(examples)
    width = len(examples[0][0])
    weights = [0.0] * width
    positive = sum(label for _, label in examples)
    intercept = math.log(positive / (size - positive))
    # The mean squared vector norm bounds the smooth loss curvature from above.
    norm = mean(1 + sum(value * value for value in values) for values, _ in examples)
    step = 1 / (0.25 * norm + penalty)
    for _ in range(1200):
        gradient = [0.0] * width
        bias_gradient = 0.0
        for values, label in examples:
            probability = sigmoid(intercept + sum(weight * value for weight, value in zip(weights, values)))
            error = probability - label
            bias_gradient += error
            for index, value in enumerate(values):
                gradient[index] += error * value
        gradients = [value / size + penalty * weight for value, weight in zip(gradient, weights)]
        intercept -= step * bias_gradient / size
        weights = [weight - step * grad for weight, grad in zip(weights, gradients)]
        if max(abs(bias_gradient / size), *(abs(value) for value in gradients)) < 1e-7:
            break
    return intercept, weights


def predict_probability(model, features, industry):
    values = vector(features, industry, model["columns"], model["industries"])
    return sigmoid(model["intercept"] + sum(weight * value for weight, value in zip(model["weights"], values)))


def scope_key(row):
    return row["market_id"], row["industry"]


def rate(labels):
    return (sum(labels) + 1) / (len(labels) + 2)


def score_metrics(evaluated):
    return {"count": len(evaluated), "closures": sum(label for label, _, _ in evaluated),
            "brier": mean((probability - label) ** 2 for label, probability, _ in evaluated),
            "baseline_brier": mean((baseline - label) ** 2 for label, _, baseline in evaluated),
            "mean_predicted": mean(probability for _, probability, _ in evaluated)}


def enough_outcomes(labels):
    counts = Counter(labels)
    return min(counts[0], counts[1]) >= MIN_SCOPE_OUTCOMES_PER_CLASS


def fit_model(training, validation, *, radius_m, metadata, penalty=DEFAULT_PENALTY):
    columns = fit_columns(training)
    industries = sorted({row["industry"] for row in training})
    examples = [(vector(row["features"], row["industry"], columns, industries), row["label"]) for row in training]
    intercept, weights = fit_logistic(examples, penalty=penalty)
    model = {"format": FORMAT, "horizon_days": HORIZON_DAYS, "radius_m": radius_m, "metadata": metadata,
             "columns": columns, "industries": industries, "intercept": intercept, "weights": weights}

    training_labels = defaultdict(list)
    training_coverage = defaultdict(list)
    industry_labels = defaultdict(list)
    for row in training:
        key = scope_key(row)
        training_labels[key].append(row["label"])
        training_coverage[(*key, coverage_key(row["features"], columns))].append(row["label"])
        industry_labels[row["industry"]].append(row["label"])
    overall_rate = rate([row["label"] for row in training])

    metrics = []
    scope_metrics = defaultdict(list)
    coverage_metrics = defaultdict(dict)
    for row in validation:
        key = scope_key(row)
        labels = training_labels.get(key) or industry_labels.get(row["industry"])
        baseline = rate(labels) if labels else overall_rate
        item = (row["label"], predict_probability(model, row["features"], row["industry"]), baseline)
        metrics.append(item)
        coverage = coverage_key(row["features"], columns)
        if (*key, coverage) in training_coverage:
            scope_metrics[key].append(item)
            coverage_metrics[key].setdefault(coverage, []).append(item)

    scopes = []
    for key, observed in sorted(scope_metrics.items()):
        score = score_metrics(observed)
        valid_coverage = {}
        if score["brier"] < score["baseline_brier"]:
            for coverage, items in sorted(coverage_metrics[key].items()):
                measured = score_metrics(items)
                if (enough_outcomes(training_coverage[(*key, coverage)])
                        and enough_outcomes(label for label, _, _ in items)
                        and measured["brier"] < measured["baseline_brier"]):
                    valid_coverage[coverage] = measured["count"]
        scopes.append({"market_id": key[0], "industry": key[1], "eligible": bool(valid_coverage),
                       "training_count": len(training_labels[key]), "validation": score,
                       "coverage": valid_coverage})
    return {**model, "validation": score_metrics(metrics), "scopes": scopes}


def load_model(path):
    model = json.loads(Path(path).read_text(encoding="utf-8-sig"))
    if not isinstance(model, dict) or model.get("format") != FORMAT or model.get("horizon_days") != HORIZON_DAYS:
        raise ValueError("지원하는 180일 생존 위험 모델 파일이 아닙니다.")
    try:
        columns = model["columns"]
        categories = model["industries"]
        weights = model["weights"]
        numbers = [model["radius_m"], model["intercept"], *weights]
        if not isinstance(columns, dict) or not set(columns) <= FEATURE_LABELS.keys():
            raise ValueError("모델 특징 목록이 올바르지 않습니다.")
        for column in columns.values():
            if column["scale"] <= 0:
                raise ValueError("모델의 표준화 배율이 올바르지 않습니다.")
            numbers.extend(column[key] for key in ("fill", "mean", "scale"))
        if (not isinstance(categories, list) or not all(isinstance(item, str) for item in categories)
                or not isinstance(weights, list) or len(weights) != 2 * len(columns) + len(categories)
                or not all(type(value) in (int, float) and math.isfinite(value) for value in numbers)
                or model["radius_m"] <= 0 or model["metadata"]["data_origin"] not in {"observed", "synthetic"}
                or model["metadata"]["outcome"] not in {"actual_closure", "permit_closure"}
                or not isinstance(model["scopes"], list)):
            raise ValueError("모델 계수 또는 검증 정보가 올바르지 않습니다.")
        if date.fromisoformat(model["metadata"]["outcomes_as_of"]).isoformat() != model["metadata"]["outcomes_as_of"]:
            raise ValueError("모델 평가 기준일이 올바르지 않습니다.")
        for scope in model["scopes"]:
            if (not all(isinstance(scope[key], str) for key in ("market_id", "industry"))
                    or type(scope["eligible"]) is not bool or not isinstance(scope["coverage"], dict)
                    or not all(isinstance(key, str) and len(key) == len(columns) and set(key) <= {"0", "1"}
                               and type(count) is int and count > 0 for key, count in scope["coverage"].items())):
                raise ValueError("모델의 상권별 검증 정보가 올바르지 않습니다.")
    except (KeyError, TypeError, OverflowError) as error:
        raise ValueError("모델 파일에 필요한 정보가 없거나 형식이 올바르지 않습니다.") from error
    return model


def estimate_risk(model, comparison, *, market_id, as_of, complete_market_inventory, prepared_features=None):
    unavailable = {"status": "unavailable", "horizon_days": HORIZON_DAYS}
    if model["metadata"]["data_origin"] != "observed":
        return {**unavailable, "reason": "가상 데이터로 학습한 검증용 모델입니다."}
    if model["metadata"].get("study_design") == FOOD_PERMIT_RESEARCH:
        return {**unavailable, "reason": "동성로 음식점 인허가 자료를 소급 재구성한 연구용 모델입니다."}
    if model["metadata"].get("study_design") == VERIFIED_STARTUP_STUDY and prepared_features is None:
        return {**unavailable, "reason": "누적 이력 모델과 동일한 공공데이터 비교 입력을 확보해 주세요."}
    try:
        snapshot = date.fromisoformat(as_of)
    except (TypeError, ValueError):
        return {**unavailable, "reason": "상권 자료 기준일을 YYYY-MM-DD로 입력하세요."}
    if snapshot.isoformat() != as_of or snapshot < date.fromisoformat(model["metadata"]["outcomes_as_of"]):
        return {**unavailable, "reason": "모델 평가 기준일 이후의 상권 자료가 필요합니다."}
    industry = industry_key(comparison["industry"])
    scope = next((row for row in model["scopes"] if row["market_id"] == market_id
                  and row["industry"] == industry and row["eligible"]), None)
    if scope is None:
        return {**unavailable, "reason": "이 상권·업종의 모델 성능이 확인되지 않았습니다."}
    features = (prepared_features if prepared_features is not None else
                model_features(comparison, complete_market_inventory=complete_market_inventory))
    coverage = coverage_key(features, model["columns"]) if features is not None else None
    if coverage not in scope["coverage"]:
        return {**unavailable, "reason": "검증에 사용한 데이터와 현재 확보한 비교 자료의 범위가 다릅니다."}

    contributions = []
    for index, name in enumerate(sorted(model["columns"])):
        value = features.get(name)
        if value is None:
            continue
        column = model["columns"][name]
        contribution = model["weights"][2 * index] * (value - column["mean"]) / column["scale"]
        if abs(contribution) >= 0.005:
            contributions.append({"feature": name, "label": FEATURE_LABELS[name],
                                  "direction": "higher" if contribution > 0 else "lower",
                                  "log_odds_contribution": round(contribution, 4)})
    contributions.sort(key=lambda row: -abs(row["log_odds_contribution"]))
    return {"status": "estimated", "horizon_days": HORIZON_DAYS, "outcome": model["metadata"]["outcome"],
            "closure_probability": round(predict_probability(model, features, industry), 4),
            "validation_count": scope["coverage"][coverage], "factors": contributions[:3]}
