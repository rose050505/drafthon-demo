from copy import deepcopy
from datetime import date
from io import BytesIO
import json
import unittest

from app import create_app
from startup_analysis import DEFAULT_PUBLIC_DATA, load_public_data
from survival_model import load_model
from v2v_startup_risk import DEFAULT_MODEL


PLAN_ID = "61019987-822a-4c43-a73d-7462cea0f690"
LEASE_ID = "aa54df3d-8903-4f0b-8879-074fa4f35ae5"
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


if __name__ == "__main__":
    unittest.main()
