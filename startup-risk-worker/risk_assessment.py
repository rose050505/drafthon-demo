"""Explain a candidate's risk signals using the surrounding-market comparison."""

from dataclasses import dataclass


@dataclass(frozen=True)
class RiskRules:
    min_numeric_peers: int = 2
    min_competitor_peers: int = 2
    min_competitor_coverage: float = 0.50
    cost_attention: float = 0.15
    cost_high: float = 0.30
    traffic_attention: float = 0.15
    traffic_high: float = 0.30
    common_channel_share: float = 0.50


DEFAULT_RULES = RiskRules()
CHECK_LABELS = {"high": "위험", "attention": "보완", "unknown": "미확인", "clear": "기준 충족"}
RISK_LABELS = {"high": "위험 높음", "attention": "보완 필요", "unknown": "판단 보류", "clear": "위험 신호 낮음"}
PRIORITY = {"high": 0, "attention": 1, "unknown": 2, "clear": 3}


def finding(key, label, status, evidence, action=None):
    return {
        "key": key,
        "label": label,
        "status": status,
        "status_label": CHECK_LABELS[status],
        "evidence": evidence,
        "action": action,
    }


def severity(gap, attention, high):
    if gap >= high:
        return "high"
    if gap >= attention:
        return "attention"
    return "clear"


def assess_cost(key, label, metric, budget, rules, adjustment):
    value = metric["candidate"]
    baseline = metric["nearby_median"]
    count = metric["sample_count"]
    if value is None:
        return finding(key, label, "unknown", f"후보의 {label} 금액이 미확인입니다.",
                       f"임대인에게 {label}과 계약 조건을 서면으로 확인하세요.")

    evidence = f"후보 {value:,.0f}원"
    if budget is not None:
        evidence += f" · 예산 상한 {budget:,.0f}원"
    if count >= rules.min_numeric_peers:
        evidence += f" · 같은 층 주변 {count}곳 중앙값 {baseline:,.0f}원"
    if budget is not None and value > budget:
        return finding(key, label, "high", f"{evidence} · 예산 {value - budget:,.0f}원 초과.", adjustment)
    if count < rules.min_numeric_peers:
        return finding(key, label, "unknown", f"{evidence} · 같은 층 비교 표본 {count}곳.",
                       f"같은 층과 비슷한 면적의 점포에서 {label} 자료를 추가로 확보하세요.")
    if baseline == 0 and value > 0:
        return finding(key, label, "unknown", f"{evidence} · 비율 비교가 어렵습니다.",
                       f"주변 점포의 {label} 0원 조건을 확인하고 유사 매물을 추가로 비교하세요.")

    gap = (value - baseline) / baseline if baseline else 0
    status = severity(gap, rules.cost_attention, rules.cost_high)
    return finding(key, label, status, f"{evidence} · 중앙값 대비 {gap:+.1%}.",
                   adjustment if status != "clear" else None)


def assess_traffic(rows, rules):
    comparisons = []
    candidate_observations = 0
    for row in rows:
        metric = row["people_per_hour"]
        value = metric["candidate"]
        baseline = metric["nearby_median"]
        if value is None:
            continue
        candidate_observations += 1
        if baseline and metric["sample_count"] >= rules.min_numeric_peers:
            comparisons.append(((baseline - value) / baseline, row))

    if not comparisons:
        return finding("traffic", "유동 인구", "unknown",
                       "후보 점포와 같은 시간대·방식으로 비교할 보행량 자료가 부족합니다.",
                       "주 영업시간에 후보 점포와 주변 점포 앞의 보행량을 같은 방식으로 다시 세세요.")

    gap, row = max(comparisons, key=lambda item: item[0])
    metric = row["people_per_hour"]
    status = severity(gap, rules.traffic_attention, rules.traffic_high)
    evidence = (f"{row['period']} · {row['method']} · 후보 시간당 {metric['candidate']:,.1f}명, "
                f"주변 {metric['sample_count']}곳 중앙값 {metric['nearby_median']:,.1f}명 · "
                f"중앙값 대비 {-gap:+.1%}. 후보 관측 {candidate_observations}개 중 "
                f"비교 가능한 {len(comparisons)}개의 최저 상대치입니다.")
    if status == "clear" and len(comparisons) < candidate_observations:
        return finding("traffic", "유동 인구", "unknown", evidence,
                       "비교 자료가 빠진 영업시간대의 주변 점포 보행량을 같은 방식으로 조사하세요.")
    return finding("traffic", "유동 인구", status, evidence,
                   "이 시간대의 점포 앞 통행과 들르는 비율을 다시 확인하고, 노출이 더 좋은 점포나 운영 시간대를 비교하세요."
                   if status != "clear" else None)


def has_competitor_sample(comparison, competitor_count, rules):
    observed = comparison["observed_peer_count"]
    return (competitor_count is not None and competitor_count > 0
            and observed >= rules.min_competitor_peers
            and observed / competitor_count >= rules.min_competitor_coverage)


def sample_description(comparison, competitor_count):
    observed = comparison["observed_peer_count"]
    if competitor_count is None:
        return "후보 업종 미확인"
    return f"동일 업종 {competitor_count}곳 중 {observed}곳 조사"


def assess_positioning(key, label, comparison, competitor_count, rules, plan_action, research_action, test_action):
    tags = comparison["candidate_tags"]
    if tags is None:
        return finding(key, label, "unknown", f"후보의 {label}이 입력되지 않았습니다.", plan_action)
    if not tags:
        return finding(key, label, "attention", f"후보가 등록한 {label} 항목이 없습니다.", plan_action)

    sample = sample_description(comparison, competitor_count)
    if not has_competitor_sample(comparison, competitor_count, rules):
        return finding(key, label, "unknown", f"{sample} · 경쟁점의 {label} 비교 자료가 부족합니다.",
                       research_action)

    unobserved = [item["tag"] for item in tags if item["nearby_count"] == 0]
    if unobserved:
        return finding(key, label, "clear", f"{sample} · 표본에서 미관측된 후보 태그 {', '.join(unobserved)}.",
                       f"‘{unobserved[0]}’에 고객이 관심을 보이는지 소규모로 확인하세요.")

    overlapping = ", ".join(f"{item['tag']} {item['nearby_count']}곳" for item in tags)
    return finding(key, label, "attention", f"{sample} · 후보 태그가 각각 경쟁점에서 관측됩니다({overlapping}).",
                   test_action)


def assess_marketing(comparison, competitor_count, rules):
    tags = comparison["candidate_tags"]
    plan = "목표 고객이 쓰는 유입 채널 하나를 정하고 문의·예약·방문 중 추적할 지표를 정하세요."
    if tags is None:
        return finding("marketing", "마케팅", "unknown", "후보의 마케팅 채널이 미입력입니다.", plan)
    if not tags:
        return finding("marketing", "마케팅", "attention", "등록된 고객 유입 채널이 없습니다.", plan)

    sample = sample_description(comparison, competitor_count)
    if not has_competitor_sample(comparison, competitor_count, rules):
        return finding("marketing", "마케팅", "unknown", f"{sample} · 채널 비교 자료가 부족합니다.",
                       "경쟁점의 지도 등록, SNS, 예약 채널을 확인해 실제 쓰는 채널을 기록하세요.")

    own_channels = {item["tag"].casefold() for item in tags}
    observed_count = comparison["observed_peer_count"]
    common_missing = [item for item in comparison["nearby_tags"]
                      if item["count"] >= rules.min_competitor_peers
                      and item["count"] / observed_count >= rules.common_channel_share
                      and item["tag"].casefold() not in own_channels]
    if common_missing:
        channels = ", ".join(f"{item['tag']} {item['count']}곳" for item in common_missing)
        return finding("marketing", "마케팅", "attention", f"{sample} · 후보 계획에 없는 공통 채널 {channels}.",
                       f"목표 고객의 ‘{common_missing[0]['tag']}’ 이용 여부를 확인한 뒤 문의나 방문 전환을 시험하세요.")
    return finding("marketing", "마케팅", "clear", f"{sample} · 후보 계획에서 빠진 공통 채널이 확인되지 않았습니다.")


def assess_space(candidate):
    required = candidate.required_features
    if required is None:
        return finding("space", "공간", "unknown", "운영에 필요한 점포 조건이 미입력입니다.",
                       "급배수, 환기, 전기 용량, 출입 동선 중 영업에 필요한 조건을 적고 현장에서 확인하세요.")
    if not required:
        return finding("space", "공간", "clear", "후보가 별도 필수 조건을 지정하지 않았습니다.")

    confirmed = {tag.casefold() for tag in candidate.space_features or ()}
    unavailable = {tag.casefold() for tag in candidate.unavailable_features or ()}
    missing = [tag for tag in required if tag.casefold() in unavailable]
    if missing:
        return finding("space", "공간", "high", f"점포에서 없다고 확인된 필수 조건 {', '.join(missing)}.",
                       f"‘{missing[0]}’ 설치 가능 여부와 공사비를 계약 전에 확인하고, 해결이 어려우면 다른 점포를 비교하세요.")
    unverified = [tag for tag in required if tag.casefold() not in confirmed]
    if unverified:
        return finding("space", "공간", "unknown", f"현장 확인이 필요한 필수 조건 {', '.join(unverified)}.",
                       "방문 시 해당 설비나 접근 조건을 확인하고 기록하세요.")
    return finding("space", "공간", "clear", f"확인된 필수 조건 {', '.join(required)}.")


def assess_risk(candidate, comparison, rules=DEFAULT_RULES):
    """Return ordered, evidence-backed screening results and proposed actions."""
    lease = comparison["lease"]
    competition = comparison["competition"]
    competitor_count = competition["same_industry_count"]
    checks = [
        assess_cost("monthly_cost", "월세·관리비", lease["monthly_total_krw"], candidate.monthly_budget_krw,
                    rules, "월세·관리비 포함 범위를 확인하고 월 부담을 줄일 수 있는 점포나 임대 조건을 비교하세요."),
        assess_space(candidate),
        assess_traffic(comparison["foot_traffic"], rules),
        assess_cost("deposit", "보증금", lease["deposit_krw"], candidate.deposit_budget_krw,
                    rules, "보증금 협상 여지와 초기 자금 여유를 확인하고 보증금이 낮은 점포의 총 월 부담도 비교하세요."),
        assess_positioning("differentiation", "차별점", competition["differentiators"], competitor_count, rules,
                           "기존 경쟁점 대신 후보 점포를 선택할 이유를 한 문장으로 적으세요.",
                           "가까운 경쟁점의 가격, 서비스 속도, 상품 구성을 살펴 차이를 기록하세요.",
                           "목표 고객에게 중요한 가격, 편의, 상품 중 한 가지를 골라 경쟁점과 다른 제안을 시험하세요."),
        assess_positioning("idea", "창업 아이디어", comparison["startup_idea"], competitor_count, rules,
                           "목표 고객, 이용 상황, 핵심 상품이나 서비스를 태그로 정리하세요.",
                           "경쟁점의 주요 고객, 이용 시간, 주력 상품을 확인해 후보 콘셉트와 비교하세요.",
                           "겹치는 콘셉트에서 해결하기 어려웠던 고객 불편 하나를 찾아 시제품이나 예약으로 검증하세요."),
        assess_marketing(comparison["marketing"], competitor_count, rules),
    ]
    checks.sort(key=lambda check: PRIORITY[check["status"]])
    assessed_count = sum(check["status"] != "unknown" for check in checks)
    status = checks[0]["status"]
    actions = {}
    for check in checks:
        action = check["action"]
        if action is not None:
            actions.setdefault(action, {"key": check["key"], "label": check["label"], "action": action})
    return {
        "status": status,
        "label": RISK_LABELS[status],
        "assessed_count": assessed_count,
        "total_checks": len(checks),
        "primary_reason": checks[0]["evidence"],
        "checks": checks,
        "priority_actions": list(actions.values()),
    }


def format_risk_summary(report):
    lines = [f"경쟁사 대비 생존 위험 진단 · 비교 반경 {report['radius_m']:g}m",
             "판정은 확보한 비교 자료와 시범 규칙에 따른 위험 신호입니다."]
    for row in report["comparisons"]:
        assessment = row["risk_assessment"]
        competitors = row["competition"]["same_industry_count"]
        known_industries = row["market"]["known_industry_count"]
        competitor_label = f"{competitors}곳" if competitors is not None else "업종 미확인"
        if competitors is not None and known_industries:
            competitor_label += f" (업종 확인 점포의 {competitors / known_industries:.0%})"
        lines.extend([
            "",
            f"{row['candidate_name']} | {assessment['label']}",
            f"주변 점포 {row['nearby_business_count']}곳 · 동일 업종 {competitor_label} · "
            f"판정 가능 {assessment['assessed_count']}/{assessment['total_checks']}개 항목",
        ])
        learned = row.get("learned_risk")
        if learned is not None:
            if learned["status"] == "estimated":
                outcome = "기록상 폐업" if learned["outcome"] == "permit_closure" else "폐업"
                lines.append(f"  [학습 모델] 180일 이내 {outcome} 위험 {learned['closure_probability']:.1%} "
                             f"(검증 {learned['validation_count']}건)")
                for factor in learned["factors"]:
                    direction = "높임" if factor["direction"] == "higher" else "낮춤"
                    lines.append(f"    - {factor['label']} · 모델 위험 추정 {direction}")
            else:
                lines.append(f"  [학습 모델] 추정 보류 · {learned['reason']}")
        lines.extend(f"  [{check['status_label']}] {check['label']} — {check['evidence']}"
                     for check in assessment["checks"])
        if assessment["priority_actions"]:
            lines.append("  우선 할 일")
        for index, item in enumerate(assessment["priority_actions"][:3], start=1):
            lines.append(f"    {index}. {item['action']}")
    if not report["comparisons"]:
        lines.append("비교할 후보가 없습니다.")
    return "\n".join(lines)
