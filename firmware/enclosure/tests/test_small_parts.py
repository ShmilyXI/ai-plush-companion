from src.parameters import EnclosureParameters
from src.small_parts import build_button_caps, build_pcb_retainer


def test_button_caps_are_two_valid_non_touching_solids():
    caps = build_button_caps(EnclosureParameters())
    solids = caps.solids().vals()
    assert len(solids) == 2
    assert all(s.isValid() and s.Volume() > 20 for s in solids)


def test_retainer_is_valid_and_fits_board_width():
    bar = build_pcb_retainer(EnclosureParameters())
    box = bar.val().BoundingBox()
    assert bar.val().isValid()
    assert len(bar.solids().vals()) == 1
    assert 30.0 <= box.xlen <= 34.0
    assert box.zlen <= 4.0
