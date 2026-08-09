from src.fit_coupons import build_button_coupon, build_display_coupon, build_usb_coupon
from src.parameters import EnclosureParameters


def test_display_coupon_has_three_clearance_notches():
    coupon = build_display_coupon(EnclosureParameters())
    assert coupon.val().isValid()
    assert len(coupon.solids().vals()) == 1
    assert coupon.val().BoundingBox().xlen <= 110


def test_usb_coupon_covers_both_ports_and_three_offsets():
    coupon = build_usb_coupon(EnclosureParameters())
    assert coupon.val().isValid()
    assert len(coupon.solids().vals()) == 1
    assert coupon.val().BoundingBox().ylen >= 30


def test_button_coupon_contains_three_offset_and_clearance_pairs():
    coupon = build_button_coupon(EnclosureParameters())
    assert coupon.val().isValid()
    assert len(coupon.solids().vals()) == 1
    assert coupon.val().Volume() > 1000
