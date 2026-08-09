from src.front_shell import component_layout
from src.parameters import EnclosureParameters


def separated_xy(a, b, margin=0.0):
    amin, amax = a.bounds
    bmin, bmax = b.bounds
    return any(
        amax[i] + margin <= bmin[i] or bmax[i] + margin <= amin[i]
        for i in (0, 1)
    )


def separated_z(a, b, margin=0.0):
    amin, amax = a.bounds
    bmin, bmax = b.bounds
    return amax[2] + margin <= bmin[2] or bmax[2] + margin <= amin[2]


def test_b2_front_layout_and_required_separation():
    p = EnclosureParameters()
    layout = component_layout(p)
    assert layout["display"].center[0] < p.outer[0] / 2
    assert layout["camera"].center[1] > p.outer[1] / 2
    assert layout["speaker"].center[0] > p.outer[0] / 2
    assert layout["microphone"].center[1] < layout["camera"].center[1]
    assert separated_xy(
        layout["speaker_chamber"], layout["microphone_pocket"], margin=2.0
    )
    assert separated_xy(layout["display"], layout["speaker"], margin=1.0)
    assert separated_z(layout["esp32"], layout["amplifier"], margin=1.0)


def test_camera_position_is_derived_from_board_measurement():
    p = EnclosureParameters()
    layout = component_layout(p)
    board_low, board_high = layout["esp32"].bounds
    assert layout["camera"].center[:2] == (
        board_low[0] + p.camera_lens_center[0],
        board_high[1] - p.camera_lens_center[1],
    )


def test_every_component_stays_inside_usable_envelope():
    p = EnclosureParameters()
    layout = component_layout(p)
    for name, item in layout.items():
        low, high = item.bounds
        assert low[0] >= p.wall, name
        assert low[1] >= p.wall, name
        assert high[0] <= p.outer[0] - p.wall, name
        assert high[1] <= p.outer[1] - p.wall, name
        assert low[2] >= p.front_panel - 1e-6, name
        assert high[2] <= p.shell_depth + 1e-6, name
