import cadquery as cq

from src.front_shell import build_front_shell, component_layout
from src.parameters import EnclosureParameters


def test_front_shell_is_one_valid_solid_with_assembly_depth():
    p = EnclosureParameters()
    shell = build_front_shell(p)
    box = shell.val().BoundingBox()
    assert shell.val().isValid()
    assert len(shell.solids().vals()) == 1
    assert (round(box.xlen, 1), round(box.ylen, 1), round(box.zlen, 1)) == (
        105.0,
        70.0,
        29.6,
    )
    assert shell.val().Volume() > 18000


def test_required_front_openings_are_clear():
    p = EnclosureParameters()
    shell = build_front_shell(p).val()
    layout = component_layout(p)
    display = layout["display"].center
    camera = layout["camera"].center
    microphone = layout["microphone"].center
    for x, y in (
        (display[0], display[1] + p.display_window_offset_y),
        (camera[0], camera[1]),
        (microphone[0], microphone[1]),
    ):
        assert not shell.isInside(cq.Vector(x, y, 1.0), 0.01)
    assert shell.isInside(cq.Vector(1.0, 35.0, 1.0), 0.01)


def test_dual_usb_side_openings_are_clear():
    p = EnclosureParameters()
    shell = build_front_shell(p).val()
    board = component_layout(p)["esp32"]
    board_low, _ = board.bounds
    for offset in p.io.usb_edge_centres:
        y = board_low[1] + offset
        assert not shell.isInside(cq.Vector(104.0, y, board.center[2]), 0.01)
