import csv
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from capture_analysis import InvalidCapture, analyze, profile, timing_stats


class CaptureAnalysisTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.manifest = {
            "schema": 1, "capture_complete": True, "reason": "duration_elapsed", "rows": 2000,
            "native_required_flags": 127, "requested_warmup_ns": 10_000_000_000,
            "requested_duration_ns": 30_000_000_000, "measured_interval_ns": 30_000_000_000,
            "interval_source": "application_render_begin_to_next_render_begin",
            "gpu_source": "available_engine_timestamp_queries_correlated_by_application_frame",
            "context": {"label": "native test fixture"},
        }
        self.rows = [dict(frame=100 + index, interval_ms=15, flags=127, render_width=1920,
                          render_height=1080, output_width=1920, output_height=1080, mode="path",
                          program="path_trace_scene.ser", spp=1, bounces=3, ray_distance=72, trace_flags=16,
                          upscaler_request="BILINEAR", upscaler_active="none", framegen_state="UNAVAILABLE",
                          denoiser="OFF", gpu_resolved=1, cpu_total_ms=3, gpu_total_ms=12, gpu_rt_ms=10)
                     for index in range(2000)]

    def write(self):
        (self.directory / "manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")
        with (self.directory / "frames.csv").open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=list(self.rows[0]))
            writer.writeheader()
            writer.writerows(self.rows)

    def report(self):
        self.write()
        return analyze(self.directory)

    def test_valid_native_capture(self):
        report = self.report()
        self.assertTrue(report["target_met_in_capture"])
        self.assertEqual(2000, report["frames"])
        self.assertEqual(30, report["duration_seconds"])
        self.assertAlmostEqual(1000 / 15, report["frame_intervals"]["one_percent_low_fps"])

    def gameplay(self):
        self.manifest["requested_warmup_ns"] = 5_000_000_000
        for row in self.rows:
            row.update(flags=99, render_width=1485, render_height=835, output_width=2560,
                       output_height=1440, upscaler_request="DLSS", upscaler_active="DLSS", denoiser="DLSS_RR")
        self.write()

    def test_qhd_dlss_profile_is_diagnostic_and_cannot_pass_native_acceptance(self):
        self.gameplay()
        report = profile(self.directory)
        self.assertTrue(report["valid_gameplay_profile"])
        self.assertFalse(report["native_acceptance"])
        self.assertNotIn("target_met_in_capture", report)
        self.assertEqual(2560, report["rendering"]["output_width"])
        self.assertEqual("DLSS", report["rendering"]["upscaler_active"])
        with self.assertRaises(InvalidCapture):
            analyze(self.directory)

    def test_profile_keeps_hitches_and_rejects_mid_capture_mode_changes(self):
        self.gameplay()
        self.rows[-1]["interval_ms"] = 1000
        self.manifest["measured_interval_ns"] += 985_000_000
        self.write()
        report = profile(self.directory)
        self.assertEqual(1000, report["frame_intervals"]["max_ms"])
        self.rows[-1]["upscaler_active"] = "none"
        self.write()
        with self.assertRaisesRegex(InvalidCapture, "resolution/upscaler/FG changed"):
            profile(self.directory)

    def test_profile_still_requires_complete_gpu_queries_world_and_focus(self):
        self.gameplay()
        for field, value in (("gpu_rt_ms", ""), ("gpu_resolved", 0), ("flags", 35)):
            original = self.rows[-1][field]
            self.rows[-1][field] = value
            self.write()
            with self.assertRaises(InvalidCapture):
                profile(self.directory)
            self.rows[-1][field] = original

    def test_lows_use_tail_mean_including_fractional_sample_counts(self):
        self.assertEqual(5, timing_stats([10] * 99 + [200])["one_percent_low_fps"])
        values = [10] * 100 + [100]
        self.assertAlmostEqual(1000 / 55, timing_stats(values)["one_percent_low_fps"])
        self.assertEqual(10, timing_stats([10] * 99 + [200])["p99_ms"])
        self.assertEqual(5, timing_stats([10] * 99 + [200])["point_one_percent_low_fps"])

    def test_hitch_is_not_filtered_and_can_fail_a_good_average(self):
        self.rows[-1]["interval_ms"] = 500
        self.manifest["measured_interval_ns"] += 485_000_000
        report = self.report()
        self.assertGreater(report["frame_intervals"]["average_fps"], 60)
        self.assertFalse(report["target_met_in_capture"])
        self.assertEqual(500, report["frame_intervals"]["max_ms"])
        self.assertEqual(1, report["frames_over_budget"])

    def test_partial_capture_and_missing_gpu_are_invalid(self):
        for field, value in (("gpu_resolved", 0), ("gpu_total_ms", ""), ("gpu_rt_ms", "NaN")):
            with self.subTest(field=field):
                original = self.rows[-1][field]
                self.rows[-1][field] = value
                with self.assertRaises(InvalidCapture):
                    self.report()
                self.rows[-1][field] = original
        self.manifest["capture_complete"] = False
        with self.assertRaisesRegex(InvalidCapture, "incomplete"):
            self.report()

    def test_resolution_upscaler_and_unproven_fg_are_invalid_even_with_good_flags(self):
        for field, value in (("render_width", 1280), ("output_height", 720), ("upscaler_request", "NIS"),
                             ("upscaler_active", "DLSS"), ("framegen_state", "ENGAGING"),
                             ("framegen_state", "FAILED"), ("mode", "rays"), ("flags", 63),
                             ("program", "requested_ser"), ("spp", 0)):
            with self.subTest(field=field, value=value):
                original = self.rows[30][field]
                self.rows[30][field] = value
                with self.assertRaises(InvalidCapture):
                    self.report()
                self.rows[30][field] = original

    def test_quality_changes_are_not_mixed_in_ab_comparison(self):
        self.rows[1]["bounces"] = 2
        with self.assertRaisesRegex(InvalidCapture, "quality/program changed"):
            self.report()

    def test_duplicate_and_dropped_frames_are_invalid(self):
        for value in (100, 102):
            self.rows[1]["frame"] = value
            with self.assertRaisesRegex(InvalidCapture, "noncontiguous"):
                self.report()

    def test_truncated_intervals_and_incorrect_manifest_are_invalid(self):
        self.manifest["measured_interval_ns"] -= 1_000_000
        with self.assertRaisesRegex(InvalidCapture, "truncated"):
            self.report()
        self.manifest["measured_interval_ns"] += 1_000_000
        self.manifest["rows"] += 1
        with self.assertRaisesRegex(InvalidCapture, "row count"):
            self.report()

    def test_insufficient_warmup_or_duration_is_invalid(self):
        for field in ("requested_duration_ns", "requested_warmup_ns"):
            previous = self.manifest[field]
            self.manifest[field] = 1
            with self.assertRaises(InvalidCapture):
                self.report()
            self.manifest[field] = previous

    def test_nonfinite_and_missing_intervals_never_become_zero(self):
        for value in ("", "nan", "inf", "-1", "0"):
            self.rows[-1]["interval_ms"] = value
            with self.assertRaises(InvalidCapture):
                self.report()
        with self.assertRaises(InvalidCapture):
            timing_stats([])

    def test_cli_failure_is_explicit_and_leaves_game_logs_untouched(self):
        self.write()
        log = self.directory / "latest.log"
        log.write_text("keep original", encoding="utf-8")
        (self.directory / "manifest.json").unlink()
        result = subprocess.run([sys.executable, str(Path(__file__).with_name("run_bench.py")), str(self.directory)],
                                capture_output=True, text=True, check=False)
        self.assertEqual(2, result.returncode)
        self.assertFalse(json.loads(result.stderr)["valid_native_capture"])
        self.assertEqual("keep original", log.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
