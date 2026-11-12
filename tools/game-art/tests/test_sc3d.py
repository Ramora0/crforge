"""sc3d (3D asset) decoding on hand-made fixtures: no game data needed."""
from __future__ import annotations

import json
import struct
import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).parent))
import sc3d_fixtures as F  # noqa: E402

from crforge_art import flatgltf, ktx2, odin, preview, rmat, sc3d, sources  # noqa: E402
from crforge_art.cli import main  # noqa: E402


def test_schema_field_order():
    sch = flatgltf.schema()
    root = [f.name for f in sch.tables["Root"]]
    assert root[0] == "accessors" and root[2] == "asset" and root[13] == "nodes" and root[18] == "textures"
    acc = [f.name for f in sch.tables["Accessor"]]
    assert acc.index("componentType") == 2 and acc[-1] == "type"
    assert sch.enums["AccessorType"][1][3] == "VEC4"
    assert sch.enums["ComponentType"][1][5126] == "FLOAT"


def test_glb_container_roundtrip():
    g = {"asset": {"version": "2.0"}, "buffers": [{"byteLength": 3}]}
    data = flatgltf.write_glb(g, b"abc")
    chunks = flatgltf.read_glb(data)
    assert [k for k, _ in chunks] == [b"JSON", b"BIN\x00"]
    assert len(data) % 4 == 0 and struct.unpack_from("<I", data, 8)[0] == len(data)
    g2, b2 = flatgltf.load(data)
    assert g2 == g and b2[:3] == b"abc"
    with pytest.raises(ValueError):
        flatgltf.read_glb(b"nope" + data[4:])


def test_fla2_decodes_to_gltf_json():
    g, b = flatgltf.load(F.model_glb())
    assert g["asset"]["version"] == "2.0"
    assert g["accessors"][0] == {"bufferView": 0, "componentType": 5123, "count": 6, "type": "SCALAR"}
    assert g["accessors"][1]["componentType"] == 0x10000 | 5126 and g["accessors"][1]["type"] == "MAT4"
    assert g["nodes"][1]["extensions"]["SC_odin_format"] == {"parent": 0}
    assert g["nodes"][0]["extensions"]["SC_odin_format"] is None
    odin_ext = g["extensions"]["SC_odin_format"]
    assert odin_ext["materials"][0]["variables"]["floats"]["metalness"] == 0.5
    assert odin_ext["meshDataInfos"][0]["vertexDescriptors"][1]["offset"] == 84
    assert g["skins"][0]["joints"] == [2, 3]
    assert "bufferView" not in g["meshes"][0]  # absent ids (-1) are dropped


def test_odin_mesh_conversion():
    g, b = flatgltf.load(F.model_glb())
    out, bin_out, _ = odin.convert(g, b)
    glb = preview.Glb(flatgltf.write_glb(out, bin_out))
    p = out["meshes"][0]["primitives"][0]
    at = p["attributes"]
    assert set(at) == {"POSITION", "NORMAL", "TANGENT", "TEXCOORD_0", "JOINTS_0", "WEIGHTS_0"}
    pos = glb.acc(at["POSITION"])
    assert pos.shape == (4, 3) and np.allclose(pos[2], [1, 2, 0])
    assert out["accessors"][at["POSITION"]]["min"] == [0, 0, 0]
    assert np.allclose(glb.acc(at["NORMAL"]), [0, 0, 1])
    assert np.allclose(glb.acc(at["TANGENT"]), [1, 0, 0, -1])
    uv = glb.acc(at["TEXCOORD_0"])
    assert np.allclose(uv[0], [0, 1]) and np.allclose(uv[2], [1, 0])  # v flipped to glTF's
    w = glb.acc(at["WEIGHTS_0"])
    j = glb.acc(at["JOINTS_0"])
    assert np.allclose(w.sum(1), 1, atol=1e-6)
    assert np.allclose(w[1], [0.75, 0.25, 0, 0], atol=1e-3)
    assert np.allclose(w[2], [1, 0, 0, 0], atol=1e-6) and j[2, 0] == 1  # the duplicate joint merged
    assert (j[w == 0] == 0).all()
    assert glb.acc(p["indices"]).reshape(-1).tolist() == [0, 1, 2, 0, 2, 3]
    assert out["accessors"][out["skins"][0]["inverseBindMatrices"]]["componentType"] == 5126
    # hierarchy from the Odin parents; the skinned mesh node becomes a root without transform
    assert out["nodes"][0]["children"] == [2] and out["nodes"][2]["children"] == [3]
    assert out["scenes"][0]["nodes"] == [0, 1] and "translation" not in out["nodes"][1]
    assert "extensionsUsed" not in out and "extensions" not in out
    assert out["meshes"][0]["extras"]["odin"]["bounds"] == [[0, 0, 0], [1, 2, 0]]


def test_packed_animation():
    g, b = flatgltf.load(F.clip_glb(frames=5))
    _, _, info = odin.convert(g, b)
    arm, hips = info["animation"]["tracks"]
    assert arm["node"] == 1 and hips["node"] == 2
    assert np.allclose(arm["rotation"][0], [0, 0, 0, 1])
    assert np.allclose(arm["rotation"][1:], [0, 0, 0.7071068, 0.7071068], atol=1e-4)  # held
    assert np.allclose(arm["translation"], [0, 1, 0])  # base translation, not animated
    assert np.allclose(hips["translation"][:, 0], [0, 0.5, 1, 1.5, 2])


def test_packed_animation_rejects_bad_size():
    g, b = flatgltf.load(F.clip_glb())
    g["extensions"]["SC_odin_format"]["animation"]["packed"]["nodes"][0]["dataSize"] += 1
    with pytest.raises(odin.OdinError):
        odin.convert(g, b)


def test_ktx2_astc_and_rgba():
    img = ktx2.decode(F.astc_const(16, 8, (65535, 32768, 0, 65535)))
    assert img.shape == (8, 16, 4) and img[0, 0].tolist() == [255, 128, 0, 255]
    hd = ktx2.header(F.astc_const(16, 16, (0, 0, 0, 65535), block=(6, 6)))
    assert hd["vkFormat"] == 166 and hd["srgb"] and ktx2.astc_block(hd["vkFormat"]) == (6, 6)
    raw = np.arange(2 * 2 * 4, dtype=np.uint8).tobytes()
    img = ktx2.decode(F.ktx2(2, 2, 37, raw, orientation="ru"))
    assert img[1, 0].tolist() == [0, 1, 2, 3]  # bottom-up rows flipped to top-down
    with pytest.raises(ValueError):
        ktx2.decode(b"\x00" * 100)


def test_texture_channel_conversions():
    rgba = np.zeros((1, 2, 4), np.uint8)
    rgba[0, 0] = [128, 128, 128, 128]   # flat
    rgba[0, 1] = [255, 255, 255, 128]   # +x
    n = sc3d.normal_map(rgba)
    assert np.abs(n[0, 0].astype(int) - [128, 128, 255]).max() <= 1
    assert n[0, 1, 0] >= 254 and abs(int(n[0, 1, 2]) - 128) <= 2
    mra = np.array([[[10, 20, 30, 255]]], np.uint8)
    assert sc3d.orm_map(mra).tolist() == [[[30, 20, 10]]]


def test_rmat():
    m = rmat.load(F.rmat_bytes("sc3d/shared/materials/base_pbr.rmat",
                               [("enableRimLight", 7, True), ("rl_pow", 1, 1.25),
                                ("rimLightColor", 4, [0.5, 1.0, 0.25, 1.0]), ("rimLightDir", 3, [0, 0, 1]),
                                ("diffuseTex", 6, "sc3d/a/b.ktx")]))
    assert m["version"] == 1 and m["base"] == "sc3d/shared/materials/base_pbr.rmat"
    p = m["params"]
    assert p["enableRimLight"] is True and p["rl_pow"] == 1.25
    assert p["rimLightColor"] == [0.5, 1.0, 0.25, 1.0] and p["rimLightDir"] == [0, 0, 1]
    assert p["diffuseTex"] == "sc3d/a/b.ktx"


def test_table_prefabs_and_prefab_walk():
    toml = ('[EXT.A]\n\tPrefabAsset = "sc3d/x/a_battle"\n[CHARACTER.B]\nPrefabAsset = "sc3d/x/b_battle"\n'
            'PrefabAsset = "sc3d/x/b2_battle"\n[CHARACTER.C]\nFileName = "sc/c.sc"\n')
    assert sc3d.table_prefabs({"csv_logic/x.toml": toml, "csv_client/y.toml": toml}) == {
        "A": ["sc3d/x/a_battle"], "B": ["sc3d/x/b_battle", "sc3d/x/b2_battle"]}
    pj = {"data": {"name": "root", "children": [
        {"name": "model", "pos": [1, 0, 0], "rot": [0, 0, 90], "scl": [2, 2, 2],
         "components": [{"objectType": 5001, "Model": "sc3d/x/m | uuid"},
                        {"objectType": 5003, "AsmAsset": "sc3d/x/asm | uuid"}]},
        {"name": "hud", "pos": [0, 0, 1.5]}],
        "components": [{"objectType": 5019, "PivotPoints": [{"PivotName": "hud"}]},
                       {"objectType": 5006, "Prefab": "sc3d/x/child | u"}]}}
    s = sc3d.walk_prefab(pj)
    m = s["models"][0]
    assert m["path"] == "sc3d/x/m" and m["asm"] == "sc3d/x/asm"
    mtx = np.array(m["transform"]).reshape(4, 4).T
    assert np.allclose(mtx @ [1, 0, 0, 1], [1, 2, 0, 1])  # 90 deg about z, scale 2, then +x 1
    assert s["pivots"] == {"hud": [0.0, 0.0, 1.5]} and s["prefabs"] == ["sc3d/x/child"]
    asm = {"States": [{"Name": "Idle", "Animation": "sc3d/x/idle | u", "StartFrame": 0, "EndFrame": 9},
                      {"Name": "R", "Type": "Random", "SubStates": ["Idle"]}]}
    assert sc3d.asm_clips(asm) == [{"state": "Idle", "clip": "sc3d/x/idle", "start": 0, "end": 9, "speed": None}]


def test_extract_end_to_end(tmp_path):
    assets = F.assets_folder(tmp_path / "game")
    out = tmp_path / "cache"
    assert main(["sc3d", "--assets", str(assets), "--out", str(out)]) == 0
    root = out / ("f" * 40)
    assert not (out / "current.json").exists() and not (root / "manifest.json").exists()
    idx = json.loads((root / "sc3d" / "index.json").read_text())
    assert idx["characters"] == {"TestUnit": ["sc3d/test/chr_prefab_test_battle"]}
    pf = idx["prefabs"]["sc3d/test/chr_prefab_test_battle"]
    assert pf["pivots"]["hudoffset"] == [0.0, 0.0, 1.5] and pf["lights"][0]["Intensity"] == 2
    model = pf["models"][0]
    assert model["glb"] == "sc3d/models/test/model/test_lod1_rig.glb"
    assert model["materialOverrides"] == {"test_lod1": "sc3d/test/material/mat_test_override"}
    mat = json.loads((root / idx["materials"]["sc3d/test/material/mat_test_override"]).read_text())
    assert mat["params"]["enableRimLight"] is True
    asm = json.loads((root / idx["asm"]["sc3d/test/animation/asm_test_battle"]).read_text())
    assert asm["DefaultState"] == "Walk"
    rep = idx["models"]["sc3d/test/model/test_lod1_rig"]
    assert [c["name"] for c in rep["clips"]] == ["test_walk"]  # other_rig_walk: other prefix
    assert rep["textures"]["sc3d/test/material/test_spec.ktx"] == "sc3d/textures/test/material/test_spec.png"
    assert (root / "sc3d/textures/test/material/test_spec.png").exists()

    glb = preview.Glb((root / model["glb"]).read_bytes())
    g = glb.g
    assert g["scenes"][0]["nodes"][0] == len(g["nodes"]) - 1 and g["nodes"][-1]["name"] == sc3d.Y_UP_ROOT
    mat = g["materials"][0]
    assert {"baseColorTexture", "metallicRoughnessTexture"} <= set(mat["pbrMetallicRoughness"])
    assert "normalTexture" in mat and "occlusionTexture" in mat
    assert mat["extras"]["sideTextures"]["teamColorTex"].endswith("test_spec.png")
    base = glb.image(g["textures"][mat["pbrMetallicRoughness"]["baseColorTexture"]["index"]]["source"])
    assert np.allclose(base[0, 0, :3], [1, 128 / 255, 0], atol=0.01)
    orm = glb.image(g["textures"][mat["occlusionTexture"]["index"]]["source"])
    assert np.allclose(orm[0, 0, :3], [0, 128 / 255, 1], atol=0.01)  # MRA (1, .5, 0) -> ORM (0, .5, 1)
    # the clip is retargeted by node name: clip node "arm" (index 1 there) drives the model's "arm"
    anim = g["animations"][0]
    assert anim["name"] == "test_walk"
    targets = {g["nodes"][c["target"]["node"]]["name"] for c in anim["channels"]}
    assert targets == {"arm", "hips"}
    img = preview.render(glb, "test_walk", 0.1, size=32)
    assert img.shape == (32, 32, 3) and img.std() > 0

    # incremental: a second run keeps the model; --only filters by row name
    assert main(["sc3d", "--assets", str(assets), "--out", str(out), "--only", "TestUnit"]) == 0
    assert json.loads((root / "sc3d" / "index.json").read_text())["models"].keys() == idx["models"].keys()


def test_source_glb_missing_is_reported(tmp_path):
    assets = F.assets_folder(tmp_path / "game")
    (assets / "sc3d/test/model/test_lod1_rig.glb").unlink()
    src = sources.from_assets_dir(assets)
    try:
        r = sc3d.run(src, tmp_path / "cache", log=lambda *_: None)
    finally:
        src.close()
    assert r.models == 0 and "sc3d/test/model/test_lod1_rig" in r.errors


APK_DIR = Path(__import__("os").environ.get("CRFORGE_ART_APK_DIR",
                                            Path(__file__).resolve().parents[4] / "crai" / "game_assets" / "apk"))
APKS = sorted(APK_DIR.glob("*.apk")) if APK_DIR.is_dir() else []


@pytest.mark.skipif(not any(p.name.startswith("split_install_time") for p in APKS),
                    reason="no Clash Royale APKs found (set CRFORGE_ART_APK_DIR)")
def test_real_game_ronin(tmp_path):
    assert main(["sc3d", "--apk", *map(str, APKS), "--out", str(tmp_path), "--only", "Ronin"]) == 0
    idx = json.loads(next(tmp_path.glob("*/sc3d/index.json")).read_text())
    rep = idx["models"]["sc3d/characters/ronin_vanilla/model/chr_ronin_lod1_rig"]
    assert rep["triangles"] > 1000 and rep["joints"] > 50
    assert {"chr_ronin_idle", "chr_ronin_walk", "chr_ronin_attack01"} <= {c["name"] for c in rep["clips"]}
    glb = preview.Glb((tmp_path / idx["content_sha"] / rep["glb"]).read_bytes())
    assert preview.render(glb, "chr_ronin_attack01", 0.5, size=64).std() > 0
