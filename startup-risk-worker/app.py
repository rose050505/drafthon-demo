"""Expose the Dongseongro analyzer to an AI worker after it has claimed a V2V job."""

from datetime import datetime, timedelta, timezone
from hmac import compare_digest
from http import HTTPStatus
import json
import os
from uuid import UUID
from wsgiref.simple_server import make_server

from compare_market import read_object
from startup_analysis import DEFAULT_PUBLIC_DATA, load_public_data
from survival_model import load_model
from v2v_startup_risk import DEFAULT_MODEL, analyze_job


ROUTE = "/api/startup-risk"
MAX_REQUEST_BYTES = 1_048_576
MAX_RESULT_CHARACTERS = 120_000


def today_in_seoul():
    return datetime.now(timezone(timedelta(hours=9))).date()


def build_submission(job, *, public_data, model, as_of):
    job = read_object(job, "작업")
    if job.get("kind") != "STARTUP_RISK":
        raise ValueError("STARTUP_RISK 유형의 작업을 전달하세요.")
    try:
        UUID(job["id"])
        lease_id = UUID(job["leaseId"])
        plan_id = UUID(job["scopeId"])
    except (KeyError, AttributeError, TypeError, ValueError) as error:
        raise ValueError("백엔드가 발급한 작업, 할당, 창업 계획 ID를 전달하세요.") from error

    inputs = read_object(job.get("input"), "작업 입력")
    plan = read_object(inputs.get("plan"), "창업 계획")
    if plan.get("id") != str(plan_id):
        raise ValueError("작업의 창업 계획 ID와 입력의 창업 계획 ID가 일치해야 합니다.")
    result = analyze_job(inputs, public_data=public_data, model=model, as_of=as_of)
    if len(json.dumps(result, ensure_ascii=False, allow_nan=False)) > MAX_RESULT_CHARACTERS:
        raise ValueError("분석 결과가 백엔드에 저장할 수 있는 크기를 초과했습니다.")
    return {"leaseId": str(lease_id), "status": "COMPLETED", "result": result}


def response(start_response, status, payload):
    body = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
    headers = [("Content-Type", "application/json; charset=utf-8"),
               ("Content-Length", str(len(body))), ("Cache-Control", "no-store")]
    start_response(f"{status.value} {status.phrase}", headers)
    return [body]


def create_app(token, public_data, model, clock=today_in_seoul):
    if not token or not token.isascii() or any(character.isspace() for character in token):
        raise ValueError("STARTUP_RISK_API_TOKEN에 공백 없는 ASCII 값을 설정하세요.")
    expected = f"Bearer {token}".encode("ascii")

    def handle(environ, start_response):
        path, method = environ.get("PATH_INFO"), environ.get("REQUEST_METHOD")
        if path == "/health" and method == "GET":
            return response(start_response, HTTPStatus.OK, {"status": "UP"})
        if path != ROUTE:
            return response(start_response, HTTPStatus.NOT_FOUND, {"error": "알 수 없는 경로입니다."})
        if method != "POST":
            return response(start_response, HTTPStatus.METHOD_NOT_ALLOWED, {"error": "POST를 사용하세요."})
        authorization = environ.get("HTTP_AUTHORIZATION", "").encode("latin-1", errors="replace")
        if not compare_digest(expected, authorization):
            return response(start_response, HTTPStatus.UNAUTHORIZED, {"error": "서버 인증이 필요합니다."})
        media_type = environ.get("CONTENT_TYPE", "").partition(";")[0].strip().lower()
        if media_type != "application/json":
            return response(start_response, HTTPStatus.UNSUPPORTED_MEDIA_TYPE,
                            {"error": "application/json 요청이 필요합니다."})
        try:
            size = int(environ.get("CONTENT_LENGTH") or "0")
        except ValueError:
            size = 0
        if size > MAX_REQUEST_BYTES:
            return response(start_response, HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "요청 크기는 1 MiB까지 지원합니다."})
        try:
            body = environ["wsgi.input"].read(size) if size > 0 else b""
            if len(body) != size:
                raise ValueError("incomplete body")
            job = json.loads(body.decode("utf-8"))
        except (ValueError, RecursionError):
            return response(start_response, HTTPStatus.BAD_REQUEST, {"error": "UTF-8 JSON을 확인하세요."})
        try:
            submission = build_submission(job, public_data=public_data, model=model, as_of=clock())
        except (KeyError, TypeError, ValueError, OverflowError, RecursionError):
            return response(start_response, HTTPStatus.UNPROCESSABLE_ENTITY,
                            {"error": "작업 유형, ID와 창업 입력을 확인하세요."})
        return response(start_response, HTTPStatus.OK, submission)

    return handle


def main():
    token = os.environ.get("STARTUP_RISK_API_TOKEN", "")
    app = create_app(token, load_public_data(DEFAULT_PUBLIC_DATA), load_model(DEFAULT_MODEL))
    bind = os.environ.get("STARTUP_RISK_BIND", "127.0.0.1")
    port = int(os.environ.get("PORT", "8765"))
    with make_server(bind, port, app) as server:
        print(f"Startup risk API listening on {bind}:{server.server_port}", flush=True)
        server.serve_forever()


if __name__ == "__main__":
    main()
