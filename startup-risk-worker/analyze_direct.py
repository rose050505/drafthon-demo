"""Run a standalone analysis from a startup JSON file and an optional V2V plan."""

import argparse
import getpass
import json
import os
from pathlib import Path
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import UUID


DEFAULT_AI_URL = "https://v2v-dongseongro-risk.onrender.com"
DEFAULT_V2V_URL = "https://v2v-drafthon-backend.onrender.com"
MAX_RESPONSE_BYTES = 1_048_576


class RejectRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def origin(url):
    parts = urlsplit(url)
    loopback = parts.scheme == "http" and parts.hostname in ("localhost", "127.0.0.1", "::1")
    if ((parts.scheme != "https" and not loopback) or not parts.hostname or parts.username is not None
            or parts.password is not None or parts.path not in ("", "/") or parts.query or parts.fragment):
        raise ValueError("서비스 기본 주소는 경로를 제외한 HTTPS 주소여야 합니다.")
    return url.rstrip("/")


def secret(name):
    value = os.environ.get(name)
    if value:
        return value
    if not sys.stdin.isatty():
        raise ValueError(f"{name} 환경 변수를 설정하세요.")
    value = getpass.getpass(f"{name}: ").strip()
    if not value:
        raise ValueError(f"{name}을 입력하세요.")
    return value


def request_json(url, token, label, payload=None, *, expected_type=dict, method=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = None
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = Request(url, data=data, headers=headers, method=method)
    try:
        with build_opener(RejectRedirects()).open(request, timeout=90 if data else 30) as response:
            body = response.read(MAX_RESPONSE_BYTES + 1)
    except HTTPError as error:
        detail = f"{label} 요청이 HTTP {error.code}를 반환했습니다."
        if label == "V2V 로그인" and error.code == 401:
            detail += " V2V 가입 이메일 또는 아이디와 비밀번호를 확인하세요."
        if label == "V2V 창업 계획 목록" and error.code == 403:
            detail += " 본인 계정이면 --v2v-login --set-founder로 예비 창업자 역할을 선택할 수 있습니다."
        if label == "AI 분석" and error.code == 422:
            try:
                message = json.loads(error.read(4096)).get("error")
                if isinstance(message, str):
                    detail += f" {message}"
            except (AttributeError, ValueError):
                pass
        raise ValueError(detail) from None
    except (URLError, TimeoutError):
        raise ValueError(f"{label} 서비스에 연결할 수 없거나 응답 시간이 초과됐습니다.") from None
    if len(body) > MAX_RESPONSE_BYTES:
        raise ValueError(f"{label} 응답이 너무 큽니다.")
    result = json.loads(body)
    if not isinstance(result, expected_type):
        raise ValueError(f"{label} 응답 형식을 확인하세요.")
    return result


def choose_v2v_plan(*, set_founder=False, test_idea=None):
    login_id = input("V2V 가입 이메일 또는 아이디: ").strip()
    password = getpass.getpass("V2V 비밀번호: ")
    if not login_id or not password:
        raise ValueError("로그인 아이디와 비밀번호를 입력하세요.")
    login_key = "email" if "@" in login_id else "username"
    session = request_json(f"{DEFAULT_V2V_URL}/api/auth/login", None, "V2V 로그인",
                           {login_key: login_id, "password": password})
    token = session.get("token")
    if not isinstance(token, str) or not token:
        raise ValueError("V2V 로그인 응답에 토큰이 없습니다.")
    account = session.get("account") or {}
    if set_founder and account.get("role") != "FOUNDER":
        role = {"role": "FOUNDER"}
        if account.get("founderBudget") is None:
            try:
                budget = int(input("창업 예산(원, 숫자만): ").strip())
            except ValueError:
                raise ValueError("창업 예산을 원 단위 정수로 입력하세요.") from None
            if not 0 <= budget <= 2**63 - 1:
                raise ValueError("창업 예산은 0원 이상의 유효한 금액이어야 합니다.")
            role["founderBudget"] = budget
        profile = request_json(f"{DEFAULT_V2V_URL}/api/me/role", token, "V2V 예비 창업자 설정",
                               role, method="PUT")
        if profile.get("role") != "FOUNDER":
            raise ValueError("V2V 활성 역할이 예비 창업자로 변경됐는지 확인하세요.")
        print("V2V 역할을 예비 창업자로 선택했습니다.")
    plans = request_json(f"{DEFAULT_V2V_URL}/api/startup-plans", token, "V2V 창업 계획 목록", expected_type=list)
    if not plans:
        if test_idea is None:
            raise ValueError("등록된 창업 계획이 없습니다. --create-test-plan으로 테스트 계획을 등록할 수 있습니다.")
        created = request_json(f"{DEFAULT_V2V_URL}/api/startup-plans", token, "V2V 테스트 계획 등록",
                               {"businessIdea": test_idea})
        plan_id = created.get("id")
        if not isinstance(plan_id, str) or not plan_id:
            raise ValueError("테스트 계획 등록 응답에 ID가 없습니다.")
        print(f"V2V 테스트 계획을 등록했습니다. {test_idea}")
        return plan_id, token
    print("최근 V2V 창업 계획")
    for number, plan in enumerate(plans, 1):
        idea = " ".join((plan.get("businessIdea") or "아이템 미입력").split())
        print(f"  {number}. {idea}")
    if len(plans) == 1:
        return plans[0]["id"], token
    try:
        selection = int(input("분석할 계획 번호: "))
    except ValueError:
        raise ValueError("목록에 표시된 숫자를 입력하세요.") from None
    if not 1 <= selection <= len(plans):
        raise ValueError("목록에 표시된 숫자를 입력하세요.")
    return plans[selection - 1]["id"], token


def include_plan(payload, plan_id, v2v_url, founder_token):
    plan_id = str(UUID(plan_id))
    plan = request_json(f"{origin(v2v_url)}/api/startup-plans/{plan_id}", founder_token, "V2V 창업 계획")
    if plan.get("id") != plan_id:
        raise ValueError("V2V가 반환한 창업 계획 ID가 요청과 다릅니다.")
    return {
        **payload,
        "planId": plan_id,
        "businessIdea": plan.get("businessIdea") or payload.get("businessIdea"),
        "industry": payload.get("industry") or plan.get("selectedIndustry"),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input_file", type=Path, help="창업 비교 입력 JSON")
    plan_options = parser.add_mutually_exclusive_group()
    plan_options.add_argument("--plan-id", help="본인의 V2V 창업 계획에서 아이템을 가져올 때 사용하는 UUID")
    plan_options.add_argument("--v2v-login", action="store_true", help="운영 V2V에 로그인한 뒤 목록에서 창업 계획 선택")
    parser.add_argument("--set-founder", action="store_true", help="본인 V2V 계정의 활성 역할을 예비 창업자로 설정")
    parser.add_argument("--create-test-plan", action="store_true", help="운영 V2V의 내 계획 목록이 비었으면 테스트 계획 등록")
    parser.add_argument("--output", type=Path, help="분석 결과 JSON을 저장할 새 파일 경로")
    args = parser.parse_args()
    if (args.set_founder or args.create_test_plan) and not args.v2v_login:
        parser.error("--set-founder와 --create-test-plan은 --v2v-login과 함께 사용하세요.")
    try:
        payload = json.loads(args.input_file.read_text(encoding="utf-8-sig"))
        if not isinstance(payload, dict):
            raise ValueError("입력 JSON은 객체여야 합니다.")
        if args.plan_id:
            payload = include_plan(payload, args.plan_id, os.environ.get("V2V_BACKEND_URL", DEFAULT_V2V_URL),
                                   secret("V2V_FOUNDER_TOKEN"))
        elif args.v2v_login:
            test_idea = None
            if args.create_test_plan:
                business_idea = payload.get("businessIdea")
                if not isinstance(business_idea, str) or not business_idea.strip():
                    raise ValueError("테스트 계획을 등록하려면 입력 JSON에 businessIdea가 필요합니다.")
                test_idea = f"[연동 테스트] {business_idea.strip()}"
                if len(test_idea) > 500:
                    raise ValueError("테스트 계획의 제목은 500자까지 입력할 수 있습니다.")
            plan_id, founder_token = choose_v2v_plan(set_founder=args.set_founder, test_idea=test_idea)
            payload = include_plan(payload, plan_id, DEFAULT_V2V_URL, founder_token)
        ai_url = origin(os.environ.get("STARTUP_RISK_AI_URL", DEFAULT_AI_URL))
        report = request_json(f"{ai_url}/api/startup-risk/direct", secret("STARTUP_RISK_API_TOKEN"), "AI 분석", payload)
        result = json.dumps(report, ensure_ascii=False, indent=2)
        if args.output is not None:
            with args.output.open("x", encoding="utf-8") as output:
                output.write(result + "\n")
    except (OSError, ValueError, EOFError) as error:
        print(f"분석 실패 · {error}", file=sys.stderr)
        return 1
    print(result if args.output is None else f"분석 결과를 저장했습니다. {args.output.resolve()}")
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    raise SystemExit(main())
