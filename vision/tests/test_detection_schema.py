from detection_schema import Detection


def test_distance_fields_only_when_valid():
    d = Detection(
        camera="left",
        confidence=0.8,
        coordinate_space="PANORAMA_PIXELS",
        distance_m=0.5,
        distance_valid=False,
    )
    out = d.to_dict()
    assert out["distanceValid"] is False
    assert "distanceM" not in out

    d.distance_valid = True
    out = d.to_dict()
    assert out["distanceM"] == 0.5
