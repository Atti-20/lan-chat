import copy
import unittest

from check_c08_evidence import EvidenceError, evaluate


def valid_payload():
    window = {
        "durationMs": 30000,
        "refreshPeriodMicros": 8333,
        "frameCount": 100,
        "p95WorkloadMicros": 2000,
        "maxWorkloadMicros": 12000,
        "workloadFramesOver250ms": 0,
        "rssDeltaBytes": 1024,
    }
    return {
        "metrics": {
            "schema": "meshx.c08-profile-performance/1",
            "platform": "ios",
            "mode": "profile",
            "physicalDevice": True,
            "formalMainEntrypoint": False,
            "firstListMessageCount": 220,
            "loadedMessageCount": 2050,
            "inputCharacters": 1000,
            "largeTextAndThemeNoFlutterException": True,
            "primarySendAccessibilityLabelPresent": True,
            "discoveryReconnectRecoveredOnline": True,
            "loginToOnlineMs": 800,
            "controllerRecoveryMs": [100, 110, 120, 130, 140],
            "flutterException": None,
            "passedMeasuredSlice": True,
            "c08GateClosed": False,
            "windows": [
                {**window, "name": "scroll-220"},
                {**window, "name": "scroll-2000"},
                {**window, "name": "input-1000", "durationMs": 1000},
            ],
        }
    }


class EvidenceTest(unittest.TestCase):
    def test_accepts_bounded_physical_slice_without_closing_gate(self):
        result = evaluate(valid_payload(), require_physical=True)
        self.assertEqual(result["status"], "PASS_MEASURED_SLICE")
        self.assertFalse(result["c08GateClosed"])

    def test_rejects_virtual_device_when_physical_required(self):
        payload = valid_payload()
        payload["metrics"]["physicalDevice"] = False
        with self.assertRaisesRegex(EvidenceError, "physical device"):
            evaluate(payload, require_physical=True)

    def test_rejects_budget_regression(self):
        payload = copy.deepcopy(valid_payload())
        payload["metrics"]["windows"][1]["p95WorkloadMicros"] = 20000
        with self.assertRaisesRegex(EvidenceError, "two refresh periods"):
            evaluate(payload)

    def test_rejects_online_recovery_over_budget(self):
        payload = valid_payload()
        payload["metrics"]["loginToOnlineMs"] = 5001
        with self.assertRaisesRegex(EvidenceError, "ONLINE exceeded"):
            evaluate(payload)


if __name__ == "__main__":
    unittest.main()
