import cadquery as cq

from src.parameters import EnclosureParameters
from src.rear_cover import build_rear_cover


def test_cover_is_valid_and_completes_32_mm_assembly_depth():
    p = EnclosureParameters()
    cover = build_rear_cover(p)
    box = cover.val().BoundingBox()
    assert cover.val().isValid()
    assert len(cover.solids().vals()) == 1
    assert (round(box.xlen, 1), round(box.ylen, 1), round(box.zlen, 1)) == (
        105.0,
        70.0,
        4.4,
    )
    assert p.shell_depth + p.rear_cover_thickness == p.outer[2]


def test_cover_has_four_m2_clearance_holes():
    p = EnclosureParameters()
    cover = build_rear_cover(p).val()
    for x, y in ((7.0, 7.0), (98.0, 7.0), (7.0, 63.0), (98.0, 63.0)):
        assert not cover.isInside(cq.Vector(x, y, 1.0), 0.01)
