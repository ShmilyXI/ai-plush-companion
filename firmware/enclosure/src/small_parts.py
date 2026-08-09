import cadquery as cq

from .parameters import EnclosureParameters


def _button_cap(p: EnclosureParameters) -> cq.Workplane:
    head = cq.Workplane("XY").circle(3.0).extrude(1.2)
    shaft = (
        cq.Workplane("XY")
        .circle(p.button_body_diameter / 2)
        .extrude(2.6)
        .translate((0, 0, 1.2))
    )
    tactile_dimple = (
        cq.Workplane("XY")
        .circle(0.7)
        .extrude(0.4)
        .translate((0, 0, 3.8))
    )
    return head.union(shaft).union(tactile_dimple).clean()


def build_button_caps(p: EnclosureParameters | None = None) -> cq.Workplane:
    p = p or EnclosureParameters()
    boot = _button_cap(p).translate((-5.0, 0, 0))
    reset = _button_cap(p).translate((5.0, 0, 0))
    return cq.Workplane("XY").newObject([boot.val(), reset.val()])


def build_pcb_retainer(p: EnclosureParameters | None = None) -> cq.Workplane:
    p = p or EnclosureParameters()
    bar = cq.Workplane("XY").box(32.0, 6.0, 3.0, centered=(True, True, False))
    hooks = (
        cq.Workplane("XY")
        .pushPoints([(-15.0, -3.5), (15.0, -3.5)])
        .box(2.0, 3.0, 3.0, centered=(True, True, False))
    )
    finger_notch = (
        cq.Workplane("XY", origin=(0, -3.0, -0.1)).circle(2.5).extrude(3.2)
    )
    return bar.union(hooks).cut(finger_notch).clean()
