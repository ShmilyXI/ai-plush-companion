import math

from src.components import Placement, rounded_plate, screw_boss, slot


def test_rounded_plate_has_requested_envelope():
    plate = rounded_plate(105, 70, 2.4, 10)
    box = plate.val().BoundingBox()
    assert (round(box.xlen, 2), round(box.ylen, 2), round(box.zlen, 2)) == (
        105.0,
        70.0,
        2.4,
    )
    assert plate.val().isValid()


def test_screw_boss_contains_pilot_hole():
    boss = screw_boss(6.5, 8.0, 1.6)
    assert boss.val().isValid()
    expected = math.pi * (6.5**2 - 1.6**2) * 8.0 / 4.0
    assert abs(boss.val().Volume() - expected) < 0.01


def test_slot_and_placement_bounds():
    opening = slot(10.0, 4.0, 2.0, 1.2)
    assert opening.val().isValid()
    placed = Placement("usb_a", (90, 20, 4), (10, 4.0, 3))
    assert placed.bounds == ((85.0, 18.0, 2.5), (95.0, 22.0, 5.5))
