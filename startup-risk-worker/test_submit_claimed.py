import unittest
from unittest.mock import patch

from submit_claimed import DEFAULT_ANALYSIS_URL, DEFAULT_BACKEND_URL, RelayError, submit_claimed
from test_app import JOB, LEASE_ID


class SubmitClaimedTest(unittest.TestCase):
    @patch("submit_claimed.request_json")
    def test_submits_only_the_claimed_risk_job(self, send):
        submission = {"leaseId": LEASE_ID, "status": "COMPLETED", "result": {"summary": "보완 필요"}}
        send.side_effect = [submission, None]
        self.assertEqual(submit_claimed(JOB, analysis_token="risk", worker_token="v2v"), JOB["id"])
        self.assertEqual(send.call_args_list[0].args, (
            DEFAULT_ANALYSIS_URL, "POST", {"Authorization": "Bearer risk"}, JOB))
        self.assertEqual(send.call_args_list[1].args, (
            f"{DEFAULT_BACKEND_URL}/api/integrations/ai/jobs/{JOB['id']}/result",
            "PUT", {"X-V2V-AI-Token": "v2v"}, submission))
        self.assertEqual(send.call_args_list[1].kwargs["expected_status"], 204)

    @patch("submit_claimed.request_json")
    def test_leaves_other_jobs_and_mismatched_leases_alone(self, send):
        with self.assertRaises(RelayError):
            submit_claimed({**JOB, "kind": "STARTUP_CHAT"}, analysis_token="risk", worker_token="v2v")
        send.assert_not_called()
        send.return_value = {"leaseId": JOB["id"], "status": "COMPLETED", "result": {}}
        with self.assertRaises(RelayError):
            submit_claimed(JOB, analysis_token="risk", worker_token="v2v")
        self.assertEqual(send.call_count, 1)


if __name__ == "__main__":
    unittest.main()
