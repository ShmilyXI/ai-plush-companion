import json

import cadquery as cq
import trimesh

from src.export_all import export_all


EXPECTED = {
    "front_shell",
    "rear_cover",
    "button_caps",
    "pcb_retainer",
    "fit_display",
    "fit_usb",
    "fit_buttons",
}


def test_export_all_writes_valid_step_stl_and_manifest(tmp_path):
    manifest = export_all(tmp_path)
    assert set(manifest["parts"]) == EXPECTED
    for name in EXPECTED:
        step = tmp_path / f"{name}.step"
        stl = tmp_path / f"{name}.stl"
        assert step.exists() and step.stat().st_size > 1000
        assert stl.exists() and stl.stat().st_size > 1000
        imported = cq.importers.importStep(str(step))
        assert imported.solids().vals(), name
        assert all(solid.isValid() for solid in imported.solids().vals()), name
        mesh = trimesh.load_mesh(stl, force="mesh")
        assert mesh.is_watertight, name
        assert mesh.volume > 20, name
        assert all(extent > 0 for extent in mesh.extents), name
    saved = json.loads((tmp_path / "manifest.json").read_text())
    assert saved == manifest
    assert saved["units"] == "mm"
    assert saved["assembled_outer_mm"] == [105.0, 70.0, 32.0]
    assert saved["parts"]["front_shell"]["extents_mm"] == [105.0, 70.0, 29.6]
