"""Analyze an already claimed STARTUP_RISK job and submit the result to V2V."""

import argparse
import json
import os
from pathlib import Path
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener
from uuid import UUID


DEFAULT_ANALYSIS_URL = "https://v2v-dongseongro-risk.onrender.com/api/startup-risk"
DEFAULT_BACKEND_URL = "https://v2v-drafthon-backend.onrender.com"
MAX_RESPONSE_BYTES = 1_048_576


class RelayError(Exception):
    """A claimed job could not be sent or completed."""


class RejectRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def endpoint(url):
    parts = urlsplit(url)
    local_http = parts.scheme == "http" and parts.hostname in ("localhost", "127.0.0.1", "::1")
    if (parts.scheme != "https" and not local_http or not parts.hostname
            or parts.username is not None or parts.password is not None or parts.query or parts.fragment):
        raise RelayError("서비스 URL은 HTTPS로 지정하세요. 로컬 테스트에는 루프백 HTTP를 사용할 수 있습니다.")
    return url.rstrip("/")


def request_json(url, method, authorization, payload, expected_status, timeout):
    request = Request(url, method=method, headers={"Content-Type": "application/json", **authorization},
                      data=json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8"))
    try:
        with build_opener(RejectRedirects()).open(request, timeout=timeout) as response:
            if response.status != expected_status:
                raise RelayError(f"{method} 요청이 HTTP {response.status}를 반환했습니다.")
            if expected_status == 204:
                return None
            body = response.read(MAX_RESPONSE_BYTES + 1)
    except HTTPError as error:
        raise RelayError(f"{method} 요청이 HTTP {error.code}를 반환했습니다.") from None
    except (URLError, TimeoutError) as error:
        raise RelayError(f"{method} 요청에 연결할 수 없거나 응답 시간이 초과됐습니다.") from error

    if len(body) > MAX_RESPONSE_BYTES:
        raise RelayError("분석 응답 크기가 허용 범위를 초과했습니다.")
    try:
        return json.loads(body)
    except ValueError as error:
        raise RelayError("분석 서비스가 JSON 결과를 반환하지 않았습니다.") from error


def submit_claimed(job, *, analysis_token, worker_token, analysis_url=DEFAULT_ANALYSIS_URL,
                   backend_url=DEFAULT_BACKEND_URL):
    if not isinstance(job, dict) or job.get("kind") != "STARTUP_RISK":
        raise RelayError("이미 할당된 STARTUP_RISK 유형의 작업을 입력하세요.")
    try:
        job_id = str(UUID(job["id"]))
        lease_id = str(UUID(job["leaseId"]))
    except (KeyError, AttributeError, TypeError, ValueError) as error:
        raise RelayError("V2V 작업의 id와 leaseId를 확인하세요.") from error
    if not analysis_token or not worker_token:
        raise RelayError("STARTUP_RISK_API_TOKEN과 V2V_AI_WORKER_TOKEN을 설정하세요.")
    target = f"{endpoint(backend_url)}/api/integrations/ai/jobs/{job_id}/result"
    submission = request_json(endpoint(analysis_url), "POST", {"Authorization": f"Bearer {analysis_token}"},
                              job, expected_status=200, timeout=90)
    if (not isinstance(submission, dict) or submission.get("leaseId") != lease_id
            or submission.get("status") != "COMPLETED" or not isinstance(submission.get("result"), dict)):
        raise RelayError("분석 서비스가 현재 작업에 해당하는 완료 결과를 반환하지 않았습니다.")
    request_json(target, "PUT", {"X-V2V-AI-Token": worker_token}, submission, expected_status=204, timeout=20)
    return job_id


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("job_file", help="할당받은 V2V 작업 JSON 파일 경로. -는 표준 입력입니다.")
    args = parser.parse_args()
    try:
        if args.job_file == "-":
            job = json.load(sys.stdin.buffer)
        else:
            job = json.loads(Path(args.job_file).read_text(encoding="utf-8-sig"))
        job_id = submit_claimed(job, analysis_token=os.environ.get("STARTUP_RISK_API_TOKEN"),
                                worker_token=os.environ.get("V2V_AI_WORKER_TOKEN"),
                                analysis_url=os.environ.get("STARTUP_RISK_API_URL", DEFAULT_ANALYSIS_URL),
                                backend_url=os.environ.get("V2V_BACKEND_URL", DEFAULT_BACKEND_URL))
    except (RelayError, OSError, ValueError) as error:
        print(f"결과 제출 실패 · {error}", file=sys.stderr)
        return 1
    print(json.dumps({"id": job_id, "status": "COMPLETED"}))
    return 0


if __name__ == "__main__":
    sys.stderr.reconfigure(encoding="utf-8")
    raise SystemExit(main())
