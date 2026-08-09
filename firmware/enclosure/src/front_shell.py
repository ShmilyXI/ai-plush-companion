import cadquery as cq

from .components import Placement, rounded_plate, screw_boss, translated_box
from .parameters import EnclosureParameters


def component_layout(p: EnclosureParameters) -> dict[str, Placement]:
    board = Placement("esp32", (*p.esp32_center_xy, 7.4), p.esp32)
    board_low, board_high = board.bounds
    camera_xy = (
        board_low[0] + p.camera_lens_center[0],
        board_high[1] - p.camera_lens_center[1],
    )
    return {
        "display": Placement("display", (*p.display_center_xy, 4.1), p.display_pocket),
        "camera": Placement(
            "camera",
            (camera_xy[0], camera_xy[1], 3.2),
            (p.lens_opening_diameter, p.lens_opening_diameter, 1.6),
        ),
        "speaker": Placement("speaker", (*p.speaker_center_xy, 6.0), p.speaker),
        "microphone": Placement(
            "microphone", (*p.microphone_center_xy, 6.5), p.microphone
        ),
        "amplifier": Placement("amplifier", (*p.amplifier_center_xy, 21.5), p.amplifier),
        "esp32": board,
        "speaker_chamber": Placement(
            "speaker_chamber", (*p.speaker_center_xy, 7.0), (37.4, 27.4, 9.0)
        ),
        "microphone_pocket": Placement(
            "microphone_pocket", (*p.microphone_center_xy, 7.0), (18.6, 18.6, 9.0)
        ),
    }


def _outer_body(p: EnclosureParameters) -> cq.Workplane:
    return rounded_plate(
        p.outer[0], p.outer[1], p.shell_depth, p.corner_radius
    ).translate((p.outer[0] / 2, p.outer[1] / 2, 0))


def _hollow_from_rear(shell: cq.Workplane, p: EnclosureParameters) -> cq.Workplane:
    cavity = rounded_plate(
        p.inner[0],
        p.inner[1],
        p.shell_depth - p.front_panel + 1.0,
        p.corner_radius - p.wall,
    ).translate((p.outer[0] / 2, p.outer[1] / 2, p.front_panel))
    return shell.cut(cavity)


def _cut_front_openings(
    shell: cq.Workplane, p: EnclosureParameters, layout: dict[str, Placement]
) -> cq.Workplane:
    display = layout["display"].center
    active_window = translated_box(
        (*p.display_window, p.front_panel + 1.0),
        (display[0], display[1] + p.display_window_offset_y, p.front_panel / 2),
    )
    shell = shell.cut(active_window)

    camera = layout["camera"].center
    throat = (
        cq.Workplane("XY")
        .center(camera[0], camera[1])
        .circle(p.lens_opening_diameter / 2)
        .extrude(p.front_panel + 1.0)
    )
    outer_chamfer = (
        cq.Workplane("XY", origin=(0, 0, -0.01))
        .center(camera[0], camera[1])
        .circle(4.0)
        .workplane(offset=1.2)
        .circle(p.lens_opening_diameter / 2)
        .loft(combine=True)
    )
    shell = shell.cut(throat).cut(outer_chamfer)

    speaker = layout["speaker"].center
    grille_points = [
        (speaker[0] + dx, speaker[1] + dy)
        for dx in (-12.0, -6.0, 0.0, 6.0, 12.0)
        for dy in (-7.5, -2.5, 2.5, 7.5)
    ]
    grille = (
        cq.Workplane("XY")
        .pushPoints(grille_points)
        .circle(1.2)
        .extrude(p.front_panel + 1.0)
    )
    shell = shell.cut(grille)

    microphone = layout["microphone"].center
    mic_port = (
        cq.Workplane("XY")
        .center(microphone[0], microphone[1])
        .circle(p.microphone_port_diameter / 2)
        .extrude(p.front_panel + 1.0)
    )
    return shell.cut(mic_port)


def _io_locations(
    p: EnclosureParameters, layout: dict[str, Placement]
) -> tuple[tuple[float, float], tuple[float, float]]:
    board_low, board_high = layout["esp32"].bounds
    usb_y = tuple(board_low[1] + value for value in p.io.usb_edge_centres)
    button_y = tuple(board_low[1] + value for value in p.io.button_edge_centres)
    button_x = board_high[0] - p.io.button_inset
    return usb_y, ((button_x, button_y[0]), (button_x, button_y[1]))


def _cut_io(
    shell: cq.Workplane, p: EnclosureParameters, layout: dict[str, Placement]
) -> cq.Workplane:
    usb_y, button_points = _io_locations(p, layout)
    board_z = layout["esp32"].center[2]
    for y in usb_y:
        opening = (
            cq.Workplane("YZ", origin=(p.outer[0] - p.wall - 0.5, y, board_z))
            .rect(p.usb_opening[0], p.usb_opening[1])
            .extrude(p.wall + 1.5)
        )
        shell = shell.cut(opening)

    for x, y in button_points:
        guide_hole = (
            cq.Workplane("XY")
            .center(x, y)
            .circle((p.button_body_diameter + p.button_slide_clearance) / 2)
            .extrude(p.front_panel + 1.0)
        )
        shell = shell.cut(guide_hole)
    return shell


def _rectangular_frame(
    center: tuple[float, float],
    inner: tuple[float, float],
    wall: float,
    height: float,
    z0: float,
) -> cq.Workplane:
    outer = cq.Workplane("XY").box(
        inner[0] + 2 * wall,
        inner[1] + 2 * wall,
        height,
        centered=(True, True, False),
    )
    void = cq.Workplane("XY").box(
        inner[0], inner[1], height + 0.2, centered=(True, True, False)
    )
    return outer.cut(void).translate((center[0], center[1], z0))


def _add_display_retention(
    shell: cq.Workplane, p: EnclosureParameters, layout: dict[str, Placement]
) -> cq.Workplane:
    display = layout["display"]
    pocket_xy = display.size[:2]
    frame = _rectangular_frame(
        display.center[:2], pocket_xy, 1.5, 3.2, p.front_panel
    )
    shell = shell.union(frame)

    ledges = []
    for dx, dy, sx, sy in (
        (-(pocket_xy[0] / 2 + 1.5), 0, 1.5, 8.0),
        (pocket_xy[0] / 2 + 1.5, 0, 1.5, 8.0),
        (0, -(pocket_xy[1] / 2 + 1.5), 8.0, 1.5),
        (0, pocket_xy[1] / 2 + 1.5, 8.0, 1.5),
    ):
        ledges.append(
            translated_box(
                (sx, sy, 1.2),
                (
                    display.center[0] + dx,
                    display.center[1] + dy,
                    p.front_panel + 3.2,
                ),
            )
        )
    for ledge in ledges:
        shell = shell.union(ledge)
    return shell


def _add_board_rails(
    shell: cq.Workplane, p: EnclosureParameters, layout: dict[str, Placement]
) -> cq.Workplane:
    board_low, board_high = layout["esp32"].bounds
    rail_length = p.esp32[0] + 1.0
    z = p.front_panel + 1.0
    lower = translated_box(
        (rail_length, 1.8, 2.0),
        ((board_low[0] + board_high[0]) / 2, board_low[1] - 0.4, z),
    )
    upper = translated_box(
        (rail_length, 1.8, 2.0),
        ((board_low[0] + board_high[0]) / 2, board_high[1] + 0.4, z),
    )
    return shell.union(lower).union(upper)


def _add_button_guides(
    shell: cq.Workplane, p: EnclosureParameters, layout: dict[str, Placement]
) -> cq.Workplane:
    _, button_points = _io_locations(p, layout)
    bore = p.button_body_diameter + p.button_slide_clearance
    for x, y in button_points:
        guide = (
            cq.Workplane("XY")
            .center(x, y)
            .circle(3.0)
            .circle(bore / 2)
            .extrude(4.0)
            .translate((0, 0, p.front_panel))
        )
        shell = shell.union(guide)
    return shell


def _add_acoustic_features(
    shell: cq.Workplane, p: EnclosureParameters, layout: dict[str, Placement]
) -> cq.Workplane:
    speaker = layout["speaker"]
    speaker_frame = _rectangular_frame(
        speaker.center[:2], (36.0, 26.0), 1.2, 9.0, p.front_panel
    )
    mic = layout["microphone"]
    mic_frame = _rectangular_frame(
        mic.center[:2], (16.6, 16.6), 1.2, 9.0, p.front_panel
    )
    return shell.union(speaker_frame).union(mic_frame)


def _add_amplifier_clip(
    shell: cq.Workplane, p: EnclosureParameters, layout: dict[str, Placement]
) -> cq.Workplane:
    amp = layout["amplifier"]
    low, _ = amp.bounds
    post_height = low[2] - p.front_panel
    for dx in (-10.3, 10.3):
        post = translated_box(
            (1.6, 19.0, post_height),
            (
                amp.center[0] + dx,
                amp.center[1],
                p.front_panel + post_height / 2,
            ),
        )
        ledge = translated_box(
            (3.0, 5.0, 1.2),
            (amp.center[0] + dx, amp.center[1], low[2] - 0.6),
        )
        shell = shell.union(post).union(ledge)
    return shell


def _add_wire_ties(shell: cq.Workplane, p: EnclosureParameters) -> cq.Workplane:
    for x, y in ((43.0, 27.0), (63.0, 34.0), (73.0, 36.0)):
        block = translated_box((6.0, 4.0, 2.0), (x, y, p.front_panel + 1.0))
        hole = (
            cq.Workplane("XY")
            .center(x, y)
            .circle(1.0)
            .extrude(3.0)
            .translate((0, 0, p.front_panel))
        )
        shell = shell.union(block).cut(hole)
    return shell


def _add_bosses_and_rear_lip(
    shell: cq.Workplane, p: EnclosureParameters
) -> cq.Workplane:
    boss_height = p.shell_depth - p.front_panel - 0.8
    for x, y in ((7.0, 7.0), (98.0, 7.0), (7.0, 63.0), (98.0, 63.0)):
        boss = screw_boss(
            p.boss_outer_diameter, boss_height, p.m2_pilot_diameter
        ).translate((x, y, p.front_panel))
        shell = shell.union(boss)

    lip_outer = rounded_plate(101.0, 66.0, p.rear_lip_depth, 8.0)
    lip_inner = rounded_plate(98.0, 63.0, p.rear_lip_depth + 0.2, 6.5)
    lip = lip_outer.cut(lip_inner).translate(
        (p.outer[0] / 2, p.outer[1] / 2, p.shell_depth - p.rear_lip_depth)
    )
    return shell.union(lip)


def build_front_shell(p: EnclosureParameters | None = None) -> cq.Workplane:
    p = p or EnclosureParameters()
    layout = component_layout(p)
    shell = _hollow_from_rear(_outer_body(p), p)
    shell = _cut_front_openings(shell, p, layout)
    shell = _cut_io(shell, p, layout)
    shell = _add_display_retention(shell, p, layout)
    shell = _add_board_rails(shell, p, layout)
    shell = _add_button_guides(shell, p, layout)
    shell = _add_acoustic_features(shell, p, layout)
    shell = _add_amplifier_clip(shell, p, layout)
    shell = _add_wire_ties(shell, p)
    shell = _add_bosses_and_rear_lip(shell, p)
    return shell.clean()
