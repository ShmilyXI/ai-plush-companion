from dataclasses import dataclass
from pathlib import Path

import cadquery as cq


@dataclass(frozen=True)
class Placement:
    name: str
    center: tuple[float, float, float]
    size: tuple[float, float, float]

    @property
    def bounds(
        self,
    ) -> tuple[tuple[float, float, float], tuple[float, float, float]]:
        return tuple(
            tuple(c + sign * s / 2 for c, s in zip(self.center, self.size))
            for sign in (-1, 1)
        )


def rounded_plate(
    width: float, height: float, depth: float, radius: float
) -> cq.Workplane:
    radius = min(radius, width / 2, height / 2)
    vertical = cq.Workplane("XY").rect(width - 2 * radius, height).extrude(depth)
    horizontal = cq.Workplane("XY").rect(width, height - 2 * radius).extrude(depth)
    corner_centres = [
        (sx * (width / 2 - radius), sy * (height / 2 - radius))
        for sx in (-1, 1)
        for sy in (-1, 1)
    ]
    corners = (
        cq.Workplane("XY").pushPoints(corner_centres).circle(radius).extrude(depth)
    )
    return vertical.union(horizontal).union(corners).clean()


def slot(width: float, height: float, depth: float, radius: float) -> cq.Workplane:
    return rounded_plate(width, height, depth, min(radius, width / 2, height / 2))


def screw_boss(
    outer_diameter: float, height: float, pilot_diameter: float
) -> cq.Workplane:
    return (
        cq.Workplane("XY")
        .circle(outer_diameter / 2)
        .circle(pilot_diameter / 2)
        .extrude(height)
    )


def translated_box(
    size: tuple[float, float, float], center: tuple[float, float, float]
) -> cq.Workplane:
    return cq.Workplane("XY").box(*size).translate(center)


def export_step_and_stl(
    solid: cq.Workplane,
    stem: Path,
    tolerance: float = 0.05,
    angular_tolerance: float = 0.1,
) -> None:
    stem.parent.mkdir(parents=True, exist_ok=True)
    cq.exporters.export(solid, str(stem.with_suffix(".step")))
    cq.exporters.export(
        solid,
        str(stem.with_suffix(".stl")),
        tolerance=tolerance,
        angularTolerance=angular_tolerance,
    )
