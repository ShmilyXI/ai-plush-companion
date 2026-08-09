from dataclasses import dataclass, field


@dataclass(frozen=True)
class PhotoDerivedIO:
    """First-prototype coordinates estimated from the supplied ruler photo."""

    usb_edge_centres: tuple[float, float] = (8.5, 21.5)
    usb_inset: float = 2.3
    button_edge_centres: tuple[float, float] = (6.5, 23.5)
    button_inset: float = 11.5
    coupon_offsets: tuple[float, float, float] = (-0.4, 0.0, 0.4)


@dataclass(frozen=True)
class EnclosureParameters:
    outer: tuple[float, float, float] = (105.0, 70.0, 32.0)
    wall: float = 2.0
    front_panel: float = 2.4
    corner_radius: float = 10.0
    rear_cover_thickness: float = 2.4
    rear_lip_depth: float = 2.0
    rear_lip_clearance: float = 0.25
    m2_pilot_diameter: float = 1.6
    m2_clearance_diameter: float = 2.4
    boss_outer_diameter: float = 6.5

    esp32: tuple[float, float, float] = (57.0, 30.0, 10.0)
    display: tuple[float, float, float] = (32.0, 45.0, 3.0)
    speaker: tuple[float, float, float] = (35.0, 25.0, 7.0)
    microphone: tuple[float, float, float] = (15.0, 15.0, 7.0)
    amplifier: tuple[float, float, float] = (19.0, 19.0, 15.0)

    display_total_clearance: float = 0.4
    display_depth_clearance: float = 0.4
    display_center_xy: tuple[float, float] = (20.5, 35.0)
    display_window: tuple[float, float] = (28.5, 28.5)
    display_window_offset_y: float = 5.0
    esp32_center_xy: tuple[float, float] = (66.5, 53.0)
    speaker_center_xy: tuple[float, float] = (83.5, 21.5)
    microphone_center_xy: tuple[float, float] = (53.0, 18.0)
    amplifier_center_xy: tuple[float, float] = (82.0, 48.0)
    camera_lens_diameter: float = 5.0
    camera_lens_center: tuple[float, float] = (20.0, 15.0)
    lens_opening_diameter: float = 6.4
    usb_shell: tuple[float, float] = (9.2, 3.2)
    usb_plug_clearance: float = 0.8
    button_body_diameter: float = 3.2
    button_slide_clearance: float = 0.3
    microphone_port_diameter: float = 2.0
    wire_channel_width: float = 3.2
    io: PhotoDerivedIO = field(default_factory=PhotoDerivedIO)

    def __post_init__(self):
        if self.wall < 1.6:
            raise ValueError("wall must be at least 1.6 mm")
        if self.front_panel < 2.0:
            raise ValueError("front panel must be at least 2.0 mm")
        if self.lens_opening_diameter <= self.camera_lens_diameter:
            raise ValueError("lens opening must exceed lens diameter")
        if self.m2_pilot_diameter >= 2.0:
            raise ValueError("M2 pilot must be smaller than the screw")

    @property
    def inner(self) -> tuple[float, float, float]:
        return (
            self.outer[0] - 2 * self.wall,
            self.outer[1] - 2 * self.wall,
            self.outer[2] - self.front_panel - self.rear_cover_thickness,
        )

    @property
    def shell_depth(self) -> float:
        return self.outer[2] - self.rear_cover_thickness

    @property
    def display_pocket(self) -> tuple[float, float, float]:
        return (
            self.display[0] + self.display_total_clearance,
            self.display[1] + self.display_total_clearance,
            self.display[2] + self.display_depth_clearance,
        )

    @property
    def usb_opening(self) -> tuple[float, float]:
        return tuple(v + self.usb_plug_clearance for v in self.usb_shell)
