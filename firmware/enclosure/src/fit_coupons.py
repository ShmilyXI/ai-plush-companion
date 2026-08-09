import cadquery as cq

from .components import rounded_plate
from .parameters import EnclosureParameters


def _marker_dots(count: int, x: float, y: float, z: float) -> cq.Workplane:
    points = [(x + index * 1.8, y) for index in range(count)]
    return (
        cq.Workplane("XY")
        .pushPoints(points)
        .circle(0.55)
        .extrude(0.6)
        .translate((0, 0, z))
    )


def build_display_coupon(p: EnclosureParameters | None = None) -> cq.Workplane:
    p = p or EnclosureParameters()
    coupon = cq.Workplane("XY").box(105.0, 18.0, 2.4, centered=(True, True, False))
    for index, (x, clearance) in enumerate(zip((-35.0, 0.0, 35.0), (0.2, 0.4, 0.6)), 1):
        notch = (
            cq.Workplane("XY")
            .box(
                p.display[0] + clearance,
                9.0,
                3.0,
                centered=(True, True, False),
            )
            .translate((x, 6.0, -0.1))
        )
        coupon = coupon.cut(notch).union(_marker_dots(index, x - 1.8, -6.5, 2.4))
    return coupon.clean()


def build_usb_coupon(p: EnclosureParameters | None = None) -> cq.Workplane:
    p = p or EnclosureParameters()
    coupon = cq.Workplane("XY").box(48.0, 34.0, 2.0, centered=(True, True, False))
    column_x = (-16.0, 0.0, 16.0)
    clearances = (0.6, 0.8, 1.0)
    for index, (x, offset, clearance) in enumerate(
        zip(column_x, p.io.coupon_offsets, clearances), 1
    ):
        opening = tuple(v + clearance for v in p.usb_shell)
        for y in (-6.5 + offset, 6.5 + offset):
            cut = rounded_plate(opening[0], opening[1], 3.0, opening[1] / 2)
            coupon = coupon.cut(cut.translate((x, y, -0.1)))
        coupon = coupon.union(_marker_dots(index, x - 1.8, -14.5, 2.0))
    return coupon.clean()


def build_button_coupon(p: EnclosureParameters | None = None) -> cq.Workplane:
    p = p or EnclosureParameters()
    coupon = cq.Workplane("XY").box(72.0, 26.0, 2.4, centered=(True, True, False))
    clearances = (0.2, 0.3, 0.4)
    for index, (x, offset, clearance) in enumerate(
        zip((-24.0, 0.0, 24.0), p.io.coupon_offsets, clearances), 1
    ):
        for y in (-4.0 + offset, 4.0 + offset):
            hole = (
                cq.Workplane("XY")
                .center(x, y)
                .circle((p.button_body_diameter + clearance) / 2)
                .extrude(3.0)
            )
            coupon = coupon.cut(hole)
        coupon = coupon.union(_marker_dots(index, x - 1.8, -10.5, 2.4))
    return coupon.clean()
