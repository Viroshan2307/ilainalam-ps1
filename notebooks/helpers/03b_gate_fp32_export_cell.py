# ==== EXTRA CELL — paste at the END of notebook 03 (same Kaggle session) and run it ====
# Why: the INT8 gate lost accuracy (only 67 % of real leaves passed). The gate is tiny, so FP32 is fine on the phone.
FP32_SRC = r"""
import sys, json, time, numpy as np, torch
from PIL import Image
from torch.export import export
from executorch.exir import to_edge_transform_and_lower
from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner
from executorch.runtime import Runtime
work, out = sys.argv[1], sys.argv[2]
sys.path.insert(0, work)
from gate_model import LeafGate
def app_tensor(p):
    im = Image.open(p); im.draft("RGB", (512, 512)); im = im.convert("RGB")
    w, h = im.size; s = min(w, h); im = im.crop(((w - s) // 2, (h - s) // 2, (w - s) // 2 + s, (h - s) // 2 + s))
    im = im.resize((512, 512), Image.BOX).resize((224, 224), Image.BOX)
    return torch.from_numpy(np.asarray(im).copy()).permute(2, 0, 1).float().div(255)[None]
m = LeafGate(pretrained=False); m.load_state_dict(torch.load(f"{out}/gate_best.pt", map_location="cpu")); m.export_mode = True; m.eval()
prog = to_edge_transform_and_lower(export(m, (torch.rand(1, 3, 224, 224),)), partitioner=[XnnpackPartitioner()]).to_executorch()
open(f"{out}/leaf_gate_fp32.pte", "wb").write(prog.buffer); print(f"gate FP32: {len(prog.buffer)/1e6:.2f} MB", flush=True)
meth = Runtime.get().load_program(f"{out}/leaf_gate_fp32.pte").load_method("forward")
g = json.load(open(f"{out}/gate_eval_paths.json")); res = {}
for k in ("leaves", "not_leaves"):
    ps, ms = [], []
    for p in g[k]:
        x = app_tensor(p); t = time.perf_counter(); ps.append(float(meth.execute([x])[0][0, 1])); ms.append(time.perf_counter() - t)
    res[k] = np.array(ps); print(k, len(ps), f"median {1000*np.median(ms):.1f} ms", flush=True)
rows = {}
for thr in [0.2, 0.3, 0.4, 0.5, 0.6, 0.7]:
    rows[thr] = {"leaves_pass": float((res["leaves"] >= thr).mean()), "not_leaves_rejected": float((res["not_leaves"] < thr).mean())}
    print(f"cut-off {thr:.1f}: leaves pass {rows[thr]['leaves_pass']:.3f} · not-leaves rejected {rows[thr]['not_leaves_rejected']:.3f}")
json.dump({str(k): v for k, v in rows.items()}, open(f"{out}/gate_fp32_check.json", "w"), indent=1)
"""
open(f"{WORK}/gate_fp32.py", "w").write(FP32_SRC)
sh(f"{ENV_DIR}/bin/python {WORK}/gate_fp32.py {WORK} {OUT_DIR}")
pkg2 = f"{WORK}/ps1_gate_fp32"; os.makedirs(pkg2, exist_ok=True)
for f in ["leaf_gate_fp32.pte", "gate_fp32_check.json"]: shutil.copy(f"{OUT_DIR}/{f}", pkg2)
print("zipped →", shutil.make_archive(pkg2, "zip", pkg2))
