import copy
import unittest

from check_c08_process_evidence import EvidenceError, evaluate


def valid_payload():
    runs = []
    for index in range(1, 7):
        runs.append(
            {
                "index": index,
                "stage": "ready",
                "restoredCredentials": index > 1,
                "firstFrameMs": 100,
                "cacheInteractiveMs": 200 if index > 1 else None,
                "onlineMs": 500,
                "readyMs": 700,
                "loadedMessageCount": 2050,
                "uniqueMessages": True,
                "peakActiveConnections": 1,
                "rssReadyBytes": 1000,
            }
        )
    payload = {
        "schema": "meshx.c08-process-lifecycle/1",
        "mode": "profile",
        "platform": "ios",
        "formalMainEntrypoint": False,
        "c08GateClosed": False,
        "runs": runs,
        "osRecoveries": [
            {
                "index": index,
                "processRun": 6,
                "backgroundAt": "2026-09-13T00:00:00Z",
                "offlineObservedAt": "2026-09-13T00:00:01Z",
                "foregroundAt": "2026-09-13T00:00:02Z",
                "onlineAfterForegroundMs": 300,
            }
            for index in range(1, 6)
        ],
    }
    host = {
        "schema": "meshx.c08-host-cold/1",
        "rows": [
            {"processRun": index, "hostLaunchToReadyMs": 1000}
            for index in range(2, 7)
        ],
    }
    return payload, host


class ProcessEvidenceTest(unittest.TestCase):
    def test_accepts_five_cold_and_real_os_recovery_rows(self):
        payload, host = valid_payload()
        result = evaluate(payload, host)
        self.assertEqual(result["status"], "PASS_PROCESS_LIFECYCLE_SLICE")
        self.assertFalse(result["c08GateClosed"])

    def test_rejects_controller_only_or_missing_os_rows(self):
        payload, host = valid_payload()
        payload["osRecoveries"] = []
        with self.assertRaisesRegex(EvidenceError, "five OS recoveries"):
            evaluate(payload, host)

    def test_rejects_slow_or_concurrent_cold_launch(self):
        payload, host = copy.deepcopy(valid_payload())
        payload["runs"][-1]["onlineMs"] = 5001
        with self.assertRaisesRegex(EvidenceError, "ONLINE exceeded"):
            evaluate(payload, host)
        payload, host = copy.deepcopy(valid_payload())
        payload["runs"][-1]["peakActiveConnections"] = 2
        with self.assertRaisesRegex(EvidenceError, "concurrent"):
            evaluate(payload, host)

    def test_rejects_slow_host_observation(self):
        payload, host = valid_payload()
        host["rows"][-1]["hostLaunchToReadyMs"] = 5001
        with self.assertRaisesRegex(EvidenceError, "host-observed"):
            evaluate(payload, host)


if __name__ == "__main__":
    unittest.main()
