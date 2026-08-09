import cadquery as cq
import trimesh


def test_cad_runtime_can_create_a_valid_solid():
    solid = cq.Workplane("XY").box(10, 20, 30)
    assert solid.val().isValid()
    assert solid.val().Volume() == 6000.0


def test_trimesh_runtime_is_available():
    mesh = trimesh.creation.box(extents=(10, 20, 30))
    assert mesh.is_watertight
    assert mesh.volume == 6000.0
