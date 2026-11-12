import tempfile
import unittest
from pathlib import Path

from bench_gi_stack import DENOISER_AB, VARIANTS, merge_index, parse_log


class BenchLogParseTest(unittest.TestCase):
    """Scene-build evidence: the TLAS-inclusive GPU 'world' span and the CPU blas/tlas_ms line.

    The 'world' GPU span (RtGpuTimer) brackets RtWorld.update(), which records the BLAS+TLAS+entity AS
    builds, so it is a TLAS-INCLUSIVE upper bound, not an isolated TLAS stamp (that needs a native ABI
    change). The blas line's 'failed=' field was dropped from the emitter, so the parser must accept both
    the current and the legacy format or it silently loses all tlas_ms/veg_quads evidence.
    """

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.log = Path(self.temp.name) / "latest.log"

    def parse(self, text):
        self.log.write_text(text, encoding="utf-8")
        return parse_log(self.log)

    GPU_LINE = ("[VKB-RT-GPU] gpu_ms world=1.250(n=900) rays=24.500(n=900) denoise=3.200(n=900) "
                "atmo=0.500(n=900) trace=20.000(n=900) pt_spatial=2.000(n=900) di_spatial=1.000(n=900) "
                "display=0.500(n=900)")
    BLAS_CURRENT = ("[VKB-RT] blas built=0 total=10 build_ms=0.00 tlas instances=10 tlas_ms=0.30 "
                    "vram_mb=100 pending=0 identical_skipped=0 veg_sections=0 veg_quads=0")
    BLAS_LEGACY = ("[VKB-RT] blas built=0 total=10 build_ms=0.00 tlas instances=10 tlas_ms=0.30 "
                   "vram_mb=100 pending=0 failed=0 identical_skipped=0 veg_sections=0 veg_quads=0")

    def test_world_span_is_captured_as_tlas_inclusive_upper_bound(self):
        ev = self.parse(self.GPU_LINE + "\n")
        self.assertEqual(1.25, ev["tlas_gpu_ms_upper_bound"])
        self.assertEqual(1, ev["gpu_spans"]["windows"])
        self.assertEqual(24.5, ev["gpu_spans"]["last"]["rays"])
        self.assertEqual(8, len(ev["gpu_spans"]["last"]))

    def test_last_gpu_window_wins(self):
        ev = self.parse("[VKB-RT-GPU] gpu_ms world=9.000(n=900) rays=1.000(n=900)\n"
                        "[VKB-RT-GPU] gpu_ms world=2.500(n=900) rays=2.000(n=900)\n")
        self.assertEqual(2, ev["gpu_spans"]["windows"])
        self.assertEqual(2.5, ev["tlas_gpu_ms_upper_bound"])

    def test_no_gpu_line_means_no_span_evidence(self):
        ev = self.parse(self.BLAS_CURRENT + "\n")
        self.assertNotIn("gpu_spans", ev)
        self.assertNotIn("tlas_gpu_ms_upper_bound", ev)

    def test_blas_current_format_without_failed_is_parsed(self):
        ev = self.parse(self.BLAS_CURRENT + "\n")
        self.assertIn("blas", ev, "current emitter drops failed=; the parser must still capture tlas_ms")
        self.assertEqual(0.30, ev["blas"]["last"]["tlas_ms"])
        self.assertEqual(10, ev["blas"]["last"]["tlas_instances"])

    def test_blas_legacy_format_with_failed_still_parsed(self):
        ev = self.parse(self.BLAS_LEGACY + "\n")
        self.assertIn("blas", ev)
        self.assertEqual(0.30, ev["blas"]["last"]["tlas_ms"])

    def test_cpu_tlas_ms_and_gpu_world_span_are_distinct_quantities(self):
        ev = self.parse(self.BLAS_CURRENT + "\n" + self.GPU_LINE + "\n")
        self.assertEqual(0.30, ev["blas"]["last"]["tlas_ms"])      # CPU wall time of prepareFrame
        self.assertEqual(1.25, ev["tlas_gpu_ms_upper_bound"])      # GPU time of the whole scene build


class BenchDenoiserVariantTest(unittest.TestCase):
    """The ReLAX A/B arm keeps the identical PT-on GI stack and only adds the expert denoiser override."""

    def test_relax_arm_is_present_and_in_the_ab(self):
        self.assertIn("denoiser_nrd_relax", VARIANTS)
        self.assertEqual(["denoiser_rr", "denoiser_nrd", "denoiser_nrd_relax"], DENOISER_AB)

    def test_relax_arm_differs_from_reblur_only_by_the_override(self):
        relax = VARIANTS["denoiser_nrd_relax"]
        self.assertEqual("nrd", relax["vkrtx.rt.path_denoiser"])
        self.assertEqual("nrd_relax", relax["vkrtx.denoiser"])
        self.assertEqual({k: v for k, v in relax.items() if k != "vkrtx.denoiser"}, VARIANTS["denoiser_nrd"])

    def test_all_denoiser_arms_share_the_pt_on_stack(self):
        for label in DENOISER_AB:
            self.assertEqual("true", VARIANTS[label]["vkrtx.rt.path_restir_pt"], label)
            self.assertEqual("true", VARIANTS[label]["vkrtx.rt.path_restir_pt_spatial"], label)


class BenchIndexMergeTest(unittest.TestCase):
    """The index rewrite must preserve non-run metadata (pending_captures) and never duplicate a label.

    A capture run previously rewrote index.json as {schema, runs} only, silently dropping any other
    top-level documentation. merge_index preserves it so wired-but-GPU-gated arms stay recorded.
    """

    def test_pending_metadata_survives_a_capture_rewrite(self):
        existing = {"schema": 1,
                    "runs": [{"label": "denoiser_rr", "capture_status": "saved", "frames": 891,
                              "file": "denoiser_rr.json"}],
                    "pending_captures": [{"label": "denoiser_nrd_relax", "status": "gpu_gated"}]}
        summary = [{"label": "denoiser_nrd", "capture_status": "saved",
                    "acceptance": {"target_met_in_capture": None, "frames": 1064}}]
        merged = merge_index(existing, summary)
        self.assertEqual(existing["pending_captures"], merged["pending_captures"])
        self.assertEqual(1, merged["schema"])
        labels = [r["label"] for r in merged["runs"]]
        self.assertIn("denoiser_rr", labels, "an untouched prior run is preserved")
        self.assertIn("denoiser_nrd", labels, "the new capture is appended")

    def test_recaptured_label_is_replaced_not_duplicated(self):
        existing = {"schema": 1, "runs": [{"label": "denoiser_rr", "capture_status": "saved",
                                           "frames": 1, "file": "denoiser_rr.json"}]}
        summary = [{"label": "denoiser_rr", "capture_status": "saved",
                    "acceptance": {"target_met_in_capture": False, "frames": 999}}]
        merged = merge_index(existing, summary)
        rr = [r for r in merged["runs"] if r["label"] == "denoiser_rr"]
        self.assertEqual(1, len(rr))
        self.assertEqual(999, rr[0]["frames"])
        self.assertFalse(rr[0]["target_met_in_capture"])

    def test_non_dict_existing_doc_degrades_to_empty(self):
        merged = merge_index([], [{"label": "x", "capture_status": "saved", "acceptance": {}}])
        self.assertEqual(1, merged["schema"])
        self.assertEqual(["x"], [r["label"] for r in merged["runs"]])


if __name__ == "__main__":
    unittest.main()