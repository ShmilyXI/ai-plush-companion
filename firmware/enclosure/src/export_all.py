import json
from pathlib import Path

import cadquery as cq
import trimesh

from .components import export_step_and_stl
from .fit_coupons import build_button_coupon, build_display_coupon, build_usb_coupon
from .front_shell import build_front_shell
from .parameters import EnclosureParameters
from .rear_cover import build_rear_cover
from .small_parts import build_button_caps, build_pcb_retainer


def _parts(p: EnclosureParameters) -> dict[str, cq.Workplane]:
    return {
        "front_shell": build_front_shell(p),
        "rear_cover": build_rear_cover(p),
        "button_caps": build_button_caps(p),
        "pcb_retainer": build_pcb_retainer(p),
        "fit_display": build_display_coupon(p),
        "fit_usb": build_usb_coupon(p),
        "fit_buttons": build_button_coupon(p),
    }


def _validate_cad(name: str, part: cq.Workplane) -> None:
    solids = part.solids().vals()
    if not solids:
        raise RuntimeError(f"{name}: no solids generated")
    if any(not solid.isValid() or solid.Volume() <= 0 for solid in solids):
        raise RuntimeError(f"{name}: invalid or zero-volume CAD solid")


def _mesh_record(name: str, stl_path: Path) -> dict:
    mesh = trimesh.load_mesh(stl_path, force="mesh")
    extents = [round(float(value), 3) for value in mesh.extents]
    volume = round(abs(float(mesh.volume)), 3)
    watertight = bool(mesh.is_watertight)
    if not watertight:
        raise RuntimeError(f"{name}: exported STL is not watertight")
    if volume <= 0 or any(value <= 0 for value in extents):
        raise RuntimeError(f"{name}: exported STL has zero volume or extent")
    return {
        "stl": stl_path.name,
        "step": stl_path.with_suffix(".step").name,
        "watertight": watertight,
        "volume_mm3": volume,
        "extents_mm": extents,
    }


def export_all(output_dir: Path) -> dict:
    p = EnclosureParameters()
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    records = {}
    for name, part in _parts(p).items():
        _validate_cad(name, part)
        stem = output_dir / name
        export_step_and_stl(part, stem)
        records[name] = _mesh_record(name, stem.with_suffix(".stl"))

    manifest = {
        "units": "mm",
        "assembled_outer_mm": list(p.outer),
        "photo_derived_io_requires_coupon_validation": True,
        "parts": records,
    }
    (output_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8"
    )
    return manifest


if __name__ == "__main__":
    export_all(Path(__file__).resolve().parents[1] / "output")
