from copy import deepcopy
from datetime import date
from io import BytesIO
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

from analyze_direct import DEFAULT_V2V_URL, choose_v2v_plan, include_plan, main
from app import create_app
from startup_analysis import DEFAULT_PUBLIC_DATA, load_public_data
from survival_model import load_model
from v2v_startup_risk import DEFAULT_MODEL


PLAN_ID = "61019987-822a-4c43-a73d-7462cea0f690"
LEASE_ID = "aa54df3d-8903-4f0b-8879-074fa4f35ae5"
DIRECT_SAMPLE = json.loads(Path(__file__).with_name("sample_direct.json").read_text(encoding="utf-8"))
JOB = {
    "id": "4aa6d19a-9502-4f52-98b1-64f03ab27eee",
    "kind": "STARTUP_RISK",
    "scopeId": PLAN_ID,
    "leaseId": LEASE_ID,
    "input": {
        "plan": {"id": PLAN_ID, "businessIdea": "테이크아웃 커피숍"},
        "location": {"latitude": 35.869, "longitude": 128.5957},
        "founderBudgetKrw": 15_000_000,
        "vacancy": {"id": "vacancy-1", "depositKrw": 20_000_000, "monthlyRentKrw": 950_000},
    },
}


class StartupRiskApiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = staticmethod(create_app("test-worker-token", load_public_data(DEFAULT_PUBLIC_DATA),
                                         load_model(DEFAULT_MODEL), clock=lambda: date(2026, 11, 26)))

    def call(self, job, token="test-worker-token", path="/api/startup-risk", method="POST"):
        body = json.dumps(job).encode("utf-8")
        status = []
        data = self.app({"PATH_INFO": path, "REQUEST_METHOD": method, "CONTENT_TYPE": "application/json",
                         "CONTENT_LENGTH": str(len(body)), "HTTP_AUTHORIZATION": f"Bearer {token}",
                         "wsgi.input": BytesIO(body)}, lambda line, headers: status.append(int(line.split()[0])))
        return status[0], json.loads(b"".join(data))

    def test_forms_v2v_submission_from_real_catalog(self):
        status, submission = self.call(JOB)
        self.assertEqual(status, 200)
        self.assertEqual(submission["status"], "COMPLETED")
        self.assertEqual(submission["leaseId"], LEASE_ID)
        result = submission["result"]
        row = result["candidates"][0]
        self.assertEqual(result["planId"], PLAN_ID)
        self.assertEqual(row["public_comparison"]["same_industry_count"], 41)
        self.assertEqual(row["risk_assessment"]["status"], "high")
        self.assertEqual(row["learned_risk"]["status"], "unavailable")
        self.assertEqual(len(result["studentProjects"]), 5)

    def test_health_and_authorization(self):
        self.assertEqual(self.call(None, token="", path="/health", method="GET"), (200, {"status": "UP"}))
        self.assertEqual(self.call(JOB, token="invalid")[0], 401)

    def test_other_job_kinds_and_mismatched_plan_are_rejected(self):
        self.assertEqual(self.call({**JOB, "kind": "STARTUP_CHAT"})[0], 422)
        unexpected = deepcopy(JOB)
        unexpected["input"]["plan"]["id"] = "6c34135b-7204-417f-8c8b-6c827af2d2ed"
        self.assertEqual(self.call(unexpected)[0], 422)

    def test_direct_analysis_calculates_risk_from_coordinates_and_monthly_costs(self):
        status, result = self.call(DIRECT_SAMPLE, path="/api/startup-risk/direct")
        self.assertEqual(status, 200)
        self.assertEqual(result["analysisStatus"], "analyzed")
        self.assertIsNone(result["planId"])
        self.assertFalse(result["industryInferred"])
        row = result["candidates"][0]
        self.assertEqual(row["public_comparison"]["same_industry_count"], 41)
        self.assertEqual(row["lease"]["monthly_total_krw"]["candidate"], 1_100_000)
        self.assertIn("100,000원 초과", result["summary"])
        self.assertEqual(len(result["studentProjects"]), 5)

    def test_direct_analysis_validates_amounts_and_pilot_coordinates(self):
        path = "/api/startup-risk/direct"
        self.assertEqual(self.call(DIRECT_SAMPLE, token="invalid", path=path)[0], 401)
        status, result = self.call({**DIRECT_SAMPLE, "monthlyManagementKrw": -1}, path=path)
        self.assertEqual(status, 422)
        self.assertIn("monthlyManagementKrw", result["error"])
        status, result = self.call({**DIRECT_SAMPLE, "latitude": 37.5}, path=path)
        self.assertEqual(status, 200)
        self.assertEqual(result["analysisStatus"], "outside_coverage")

    @patch("analyze_direct.request_json")
    def test_cli_reads_the_founders_plan_with_its_own_credential(self, get_plan):
        get_plan.return_value = {"id": PLAN_ID, "businessIdea": "동성로 커피 포장", "selectedIndustry": "카페"}
        result = include_plan({"latitude": 35.869, "longitude": 128.5957}, PLAN_ID, "http://127.0.0.1:8080", "founder")
        get_plan.assert_called_once_with(f"http://127.0.0.1:8080/api/startup-plans/{PLAN_ID}", "founder", "V2V 창업 계획")
        self.assertEqual(result["planId"], PLAN_ID)
        self.assertEqual(result["businessIdea"], "동성로 커피 포장")
        self.assertEqual(result["industry"], "카페")
        self.assertEqual(result["latitude"], 35.869)

    @patch("analyze_direct.request_json")
    @patch("analyze_direct.getpass.getpass", return_value="fixture-password")
    @patch("builtins.input", side_effect=["founder@example.test", "2"])
    @patch("builtins.print")
    def test_cli_logs_in_and_selects_a_plan_without_a_swagger_token(self, print_line, read_line, read_password, request):
        request.side_effect = [
            {"token": "fixture-token"},
            [{"id": str(LEASE_ID), "businessIdea": "다른 계획"}, {"id": PLAN_ID, "businessIdea": "동성로 카페"}],
        ]
        self.assertEqual(choose_v2v_plan(), (PLAN_ID, "fixture-token"))
        self.assertEqual(request.call_args_list[0].args, (
            f"{DEFAULT_V2V_URL}/api/auth/login", None, "V2V 로그인",
            {"email": "founder@example.test", "password": "fixture-password"},
        ))
        self.assertEqual(request.call_args_list[1].args, (
            f"{DEFAULT_V2V_URL}/api/startup-plans", "fixture-token", "V2V 창업 계획 목록",
        ))
        self.assertIs(request.call_args_list[1].kwargs["expected_type"], list)

    @patch("analyze_direct.request_json")
    @patch("analyze_direct.getpass.getpass", return_value="fixture-password")
    @patch("builtins.input", side_effect=["fixture-founder", "25000000"])
    @patch("builtins.print")
    def test_cli_can_set_founder_role_before_reading_plans(self, print_line, read_line, read_password, request):
        request.side_effect = [
            {"token": "fixture-token", "account": {"role": "OWNER", "founderBudget": None}},
            {"role": "FOUNDER"},
            [{"id": PLAN_ID, "businessIdea": "동성로 카페"}],
        ]
        self.assertEqual(choose_v2v_plan(set_founder=True), (PLAN_ID, "fixture-token"))
        self.assertEqual(request.call_args_list[1].args, (
            f"{DEFAULT_V2V_URL}/api/me/role", "fixture-token", "V2V 예비 창업자 설정",
            {"role": "FOUNDER", "founderBudget": 25_000_000},
        ))
        self.assertEqual(request.call_args_list[1].kwargs["method"], "PUT")
        self.assertEqual(request.call_args_list[2].args[0], f"{DEFAULT_V2V_URL}/api/startup-plans")

    @patch("analyze_direct.request_json")
    @patch("analyze_direct.getpass.getpass", return_value="fixture-password")
    @patch("builtins.input", return_value="fixture-founder")
    @patch("builtins.print")
    def test_cli_creates_a_test_plan_only_when_the_list_is_empty(self, print_line, read_line, read_password, request):
        test_idea = "[연동 테스트] 동성로 카페"
        session = {"token": "fixture-token", "account": {"role": "FOUNDER"}}
        plan = {"id": PLAN_ID, "businessIdea": test_idea}
        request.side_effect = [session, [], plan]
        self.assertEqual(choose_v2v_plan(test_idea=test_idea), (PLAN_ID, "fixture-token"))
        self.assertEqual(request.call_args_list[2].args, (
            f"{DEFAULT_V2V_URL}/api/startup-plans", "fixture-token", "V2V 테스트 계획 등록", {"businessIdea": test_idea},
        ))
        request.reset_mock()
        request.side_effect = [session, [plan]]
        self.assertEqual(choose_v2v_plan(test_idea=test_idea), (PLAN_ID, "fixture-token"))
        self.assertEqual(request.call_count, 2)

    @patch("builtins.print")
    @patch("analyze_direct.secret", return_value="fixture-token")
    @patch("analyze_direct.request_json")
    def test_cli_saves_only_report_json_without_overwriting(self, request, get_secret, print_line):
        with TemporaryDirectory() as directory:
            result = Path(directory) / "report.json"
            args = ["analyze_direct.py", str(Path(__file__).with_name("sample_direct.json")), "--output", str(result)]
            first_report = {"summary": "위험 높음", "planId": PLAN_ID}
            request.side_effect = [first_report, {"summary": "보완 필요"}]
            with patch("sys.argv", args):
                self.assertEqual(main(), 0)
                self.assertEqual(main(), 1)
            self.assertEqual(json.loads(result.read_text(encoding="utf-8")), first_report)


if __name__ == "__main__":
    unittest.main()
