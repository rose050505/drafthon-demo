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


def request_json(url, token, label, payload=None):
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/json"}
    data = None
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = Request(url, data=data, headers=headers)
    try:
        with build_opener(RejectRedirects()).open(request, timeout=90 if data else 30) as response:
            body = response.read(MAX_RESPONSE_BYTES + 1)
    except HTTPError as error:
        detail = f"{label} 요청이 HTTP {error.code}를 반환했습니다."
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
    if not isinstance(result, dict):
        raise ValueError(f"{label} 결과가 JSON 객체가 아닙니다.")
    return result


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
    parser.add_argument("--plan-id", help="본인의 V2V 창업 계획에서 아이템을 가져올 때 사용하는 UUID")
    args = parser.parse_args()
    try:
        payload = json.loads(args.input_file.read_text(encoding="utf-8-sig"))
        if not isinstance(payload, dict):
            raise ValueError("입력 JSON은 객체여야 합니다.")
        if args.plan_id:
            payload = include_plan(payload, args.plan_id, os.environ.get("V2V_BACKEND_URL", DEFAULT_V2V_URL),
                                   secret("V2V_FOUNDER_TOKEN"))
        ai_url = origin(os.environ.get("STARTUP_RISK_AI_URL", DEFAULT_AI_URL))
        report = request_json(f"{ai_url}/api/startup-risk/direct", secret("STARTUP_RISK_API_TOKEN"), "AI 분석", payload)
    except (OSError, ValueError) as error:
        print(f"분석 실패 · {error}", file=sys.stderr)
        return 1
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    raise SystemExit(main())
