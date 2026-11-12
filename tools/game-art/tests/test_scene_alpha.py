import numpy as np

from crforge_art.scene import straight_alpha


def test_straight_alpha_roundtrip():
    rng = np.random.default_rng(0)
    straight = rng.integers(0, 256, (64, 64, 4), dtype=np.uint8)
    a = straight[..., 3:4].astype(np.uint32)
    premul = np.concatenate([(straight[..., :3] * a + 127) // 255, a], -1).astype(np.uint8)
    back = straight_alpha(premul)
    assert back.dtype == np.uint8 and back.shape == premul.shape
    assert (back[..., 3] == straight[..., 3]).all()
    assert (back[..., :3][premul[..., 3] == 0] == 0).all()
    # re-premultiplying the result gives back the game's pixels exactly
    rep = (back[..., :3].astype(np.uint32) * a + 127) // 255
    assert (rep == premul[..., :3]).all()
