import cadquery as cq

from .components import rounded_plate
from .parameters import EnclosureParameters


def build_rear_cover(p: EnclosureParameters | None = None) -> cq.Workplane:
    p = p or EnclosureParameters()
    center = (p.outer[0] / 2, p.outer[1] / 2, 0)
    cover = rounded_plate(
        p.outer[0], p.outer[1], p.rear_cover_thickness, p.corner_radius
    ).translate(center)

    tongue_outer = rounded_plate(97.5, 62.5, p.rear_lip_depth, 6.25)
    tongue_inner = rounded_plate(94.5, 59.5, p.rear_lip_depth + 0.2, 4.75)
    tongue = tongue_outer.cut(tongue_inner).translate(
        (p.outer[0] / 2, p.outer[1] / 2, p.rear_cover_thickness)
    )
    cover = cover.union(tongue)

    for x, y in ((7.0, 7.0), (98.0, 7.0), (7.0, 63.0), (98.0, 63.0)):
        hole = (
            cq.Workplane("XY")
            .center(x, y)
            .circle(p.m2_clearance_diameter / 2)
            .extrude(p.rear_cover_thickness + p.rear_lip_depth + 1.0)
        )
        cover = cover.cut(hole)
    return cover.clean()
