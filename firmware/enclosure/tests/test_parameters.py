import pytest

from src.parameters import EnclosureParameters, PhotoDerivedIO


def test_confirmed_default_dimensions():
    p = EnclosureParameters()
    assert p.outer == (105.0, 70.0, 32.0)
    assert p.esp32 == (57.0, 30.0, 10.0)
    assert p.display == (32.0, 45.0, 3.0)
    assert p.speaker == (35.0, 25.0, 7.0)
    assert p.microphone == (15.0, 15.0, 7.0)
    assert p.amplifier == (19.0, 19.0, 15.0)
    assert p.camera_lens_diameter == 5.0
    assert p.camera_lens_center == (20.0, 15.0)


def test_photo_derived_io_is_explicit_and_symmetric():
    io = PhotoDerivedIO()
    assert io.usb_edge_centres == (8.5, 21.5)
    assert io.button_edge_centres == (6.5, 23.5)
    assert io.coupon_offsets == (-0.4, 0.0, 0.4)


def test_derived_inner_envelope_and_openings():
    p = EnclosureParameters()
    assert p.inner[:2] == (101.0, 66.0)
    assert p.inner[2] == pytest.approx(27.2)
    assert p.display_pocket == (32.4, 45.4, 3.4)
    assert p.lens_opening_diameter == 6.4
    assert p.usb_opening == (10.0, 4.0)


def test_visible_layout_is_parameterized():
    p = EnclosureParameters()
    assert p.display_center_xy == (20.5, 35.0)
    assert p.esp32_center_xy == (66.5, 53.0)
    assert p.speaker_center_xy == (83.5, 21.5)
    assert p.microphone_center_xy == (53.0, 18.0)
    assert p.display_window == (28.5, 28.5)
    assert p.display_window_offset_y == 5.0


@pytest.mark.parametrize(
    "changes, message",
    [
        ({"wall": 1.0}, "wall must be at least 1.6 mm"),
        ({"front_panel": 1.5}, "front panel must be at least 2.0 mm"),
        ({"lens_opening_diameter": 4.5}, "lens opening must exceed lens diameter"),
        ({"m2_pilot_diameter": 2.1}, "M2 pilot must be smaller than the screw"),
    ],
)
def test_invalid_print_parameters_are_rejected(changes, message):
    with pytest.raises(ValueError, match=message):
        EnclosureParameters(**changes)
