"""AP en VAL de RT-DETRv2 (modelo «raw» sin posprocesador) con TFLite, ONNX o PyTorch, con el preprocesado de la app Android.

SOLO VAL (anotaciones COCO de VAL indicadas en RT_VAL_JSON), nunca TEST. Pensado para validar en el PC la conversion a TFLite del
modelo final antes de ponerlo en la app (RTDETRInference.kt).

Dos fases:
  run    ejecuta un modelo sobre VAL y guarda las salidas crudas (pred_logits [N,150,8], pred_boxes [N,150,4] cxcywh)
  score  puntua uno o varios .npz con dos posprocesados:
         - torch: el de PyTorch (sigmoide, top-300 sobre 150x8, sin NMS). Mide la perdida por la conversion.
         - app:   el de la app (sigmoide, umbral, NMS por clase IoU 0,5, maximo 100). Mide lo que ve el usuario.
         y barre el umbral del modo app (P, R, F1 a IoU 0,5 por clase, y AP/AP50 del conjunto umbralizado).

Preprocesados (--prep):
  android       la app: ImagePreprocessor reduce el lado largo a 640 (createScaledBitmap bilineal, sin antialias) y
                RTDETRInference lo estira a 640x640 (bilineal); RGB, /255, sin media/desviacion, sin letterbox.
                Emulado con cv2.INTER_LINEAR en los dos pasos.
  android_area  igual pero el primer paso (reduccion) con INTER_AREA (si Android hiciera filtrado al reducir).
  area          un solo paso cv2.INTER_AREA (el de la Jetson, build_int8_engine.py).
  pil           un solo paso, bilineal con antialias de PIL (el de la validacion de PyTorch/torchvision).

Uso (en el PC, CPU):
  python eval_tflite_val.py run --backend tflite --model m.tflite --prep android --raw out/tfl16_android.npz
  python eval_tflite_val.py run --backend onnx   --model m.onnx   --prep android --raw out/onnx_android.npz
  (en el contenedor rt_detrv2, PYTHONPATH=/app/RT-DETR)
  python eval_tflite_val.py run --backend pt --config cfg.yml --ckpt best.pth --prep android --raw out/pt_android.npz
  python eval_tflite_val.py score --raw out/pt_android.npz out/tfl16_android.npz --json out/resumen.json
"""
import argparse
import json
import os
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import cv2
import numpy as np

VAL = Path(os.environ.get("RT_VAL_JSON", "val.json"))   # RT_VAL_JSON: anotaciones COCO del conjunto VAL
NQ, NC, SIZE = 150, 8, 640


def data_root():
    """Raiz de las imagenes: variable de entorno DATA_ROOT (por defecto /data)."""
    p = os.environ.get("DATA_ROOT", "/data")
    if os.path.isdir(p):
        return p
    raise SystemExit(f"no encuentro el directorio de datos ({p}); define DATA_ROOT")


def preprocess(bgr, prep):
    """BGR uint8 -> float32 [1,640,640,3] RGB /255 (NHWC)."""
    h, w = bgr.shape[:2]
    if prep in ("android", "android_area"):
        if max(h, w) > SIZE:
            s = SIZE / max(h, w)
            interp = cv2.INTER_LINEAR if prep == "android" else cv2.INTER_AREA
            bgr = cv2.resize(bgr, (int(w * s), int(h * s)), interpolation=interp)
        img = cv2.resize(bgr, (SIZE, SIZE), interpolation=cv2.INTER_LINEAR)
    elif prep == "area":
        img = cv2.resize(bgr, (SIZE, SIZE), interpolation=cv2.INTER_AREA)
    elif prep == "pil":
        from PIL import Image
        rgb = Image.fromarray(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB))
        img = np.asarray(rgb.resize((SIZE, SIZE), Image.BILINEAR))[:, :, ::-1]
    else:
        raise ValueError(prep)
    return (img[:, :, ::-1].astype(np.float32) / 255.0)[None]


class TFLite:
    def __init__(self, path, threads):
        from ai_edge_litert.interpreter import Interpreter
        self.it = Interpreter(model_path=path, num_threads=threads)
        self.it.allocate_tensors()
        self.inp = self.it.get_input_details()[0]["index"]
        outs = self.it.get_output_details()
        # los nombres pueden venir cruzados: la forma manda (ultimo eje 4 = cajas), como en la app
        self.i_box = next(o["index"] for o in outs if o["shape"][-1] == 4)
        self.i_log = next(o["index"] for o in outs if o["shape"][-1] == NC)

    def __call__(self, x):
        self.it.set_tensor(self.inp, x)
        self.it.invoke()
        return self.it.get_tensor(self.i_log)[0].copy(), self.it.get_tensor(self.i_box)[0].copy()


class Onnx:
    def __init__(self, path, threads):
        import onnxruntime as ort
        so = ort.SessionOptions()
        so.intra_op_num_threads = threads
        self.s = ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])

    def __call__(self, x):
        lg, bx = self.s.run(None, {"images": np.ascontiguousarray(x.transpose(0, 3, 1, 2))})
        return lg[0], bx[0]


class Torch:
    def __init__(self, config, ckpt, threads):
        import torch
        from src.core import YAMLConfig
        torch.set_num_threads(threads)
        self.torch = torch
        cfg = YAMLConfig(config, resume=None)
        ck = torch.load(ckpt, map_location="cpu", weights_only=False)
        state = ck["ema"]["module"] if ck.get("ema") else ck["model"]
        cfg.model.load_state_dict(state)
        self.m = cfg.model.eval()

    def __call__(self, x):
        with self.torch.no_grad():
            out = self.m(self.torch.from_numpy(np.ascontiguousarray(x.transpose(0, 3, 1, 2))))
        return out["pred_logits"][0].numpy(), out["pred_boxes"][0].numpy()


def cmd_run(a):
    val = json.load(open(VAL, encoding="utf8"))
    images = sorted(val["images"], key=lambda d: d["id"])[: a.n or None]
    root = data_root()
    if a.backend == "tflite":
        model = TFLite(a.model, a.threads)
    elif a.backend == "onnx":
        model = Onnx(a.model, a.threads)
    else:
        model = Torch(a.config, a.ckpt, a.threads)

    def load(im):
        bgr = cv2.imread(os.path.join(root, im["file_name"]))
        return preprocess(bgr, a.prep), bgr.shape[1], bgr.shape[0]

    logits, boxes, sizes = [], [], []
    t0 = time.time()
    with ThreadPoolExecutor(3) as ex:
        for k, (x, w, h) in enumerate(ex.map(load, images)):
            lg, bx = model(x)
            logits.append(lg), boxes.append(bx), sizes.append((w, h))
            if (k + 1) % 100 == 0:
                print(f"{k + 1}/{len(images)} {(time.time() - t0) / (k + 1):.2f} s/img", flush=True)
    os.makedirs(os.path.dirname(os.path.abspath(a.raw)), exist_ok=True)
    np.savez_compressed(a.raw, logits=np.stack(logits), boxes=np.stack(boxes), sizes=np.array(sizes),
                        ids=np.array([im["id"] for im in images]), prep=a.prep, model=str(a.model or a.ckpt))
    print("guardado", a.raw, f"{time.time() - t0:.0f} s")


# ---------------------------------------------------------------- posprocesado
def sigmoid(x):
    return 1.0 / (1.0 + np.exp(-x))


def to_xyxy(b, w, h, clip=False):
    cx, cy, bw, bh = b[..., 0], b[..., 1], b[..., 2], b[..., 3]
    x1, y1, x2, y2 = cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2
    out = np.stack([x1, y1, x2, y2], -1)
    if clip:
        out = np.clip(out, 0, 1)
    return out * np.array([w, h, w, h])


def post_torch(lg, bx, w, h):
    s = sigmoid(lg).reshape(-1)
    top = np.argsort(-s)[:300]
    q, c = top // NC, top % NC
    return to_xyxy(bx[q], w, h), s[top], c


def iou_one(b, bs):
    xx1, yy1 = np.maximum(b[0], bs[:, 0]), np.maximum(b[1], bs[:, 1])
    xx2, yy2 = np.minimum(b[2], bs[:, 2]), np.minimum(b[3], bs[:, 3])
    inter = np.clip(xx2 - xx1, 0, None) * np.clip(yy2 - yy1, 0, None)
    a1 = (b[2] - b[0]) * (b[3] - b[1])
    a2 = (bs[:, 2] - bs[:, 0]) * (bs[:, 3] - bs[:, 1])
    return inter / np.maximum(a1 + a2 - inter, 1e-9)


def post_app(lg, bx, w, h, thr, nms_iou=0.5, max_det=100):
    """Como RTDETRInference.postProcess + DetectionNms: cada (consulta, clase) con sigmoide >= umbral es un candidato."""
    s = sigmoid(lg)
    q, c = np.nonzero((s >= thr) & (lg >= -5.0))
    if len(q) == 0:
        return np.zeros((0, 4)), np.zeros(0), np.zeros(0, int)
    boxes = to_xyxy(bx[q], 1.0, 1.0, clip=True)
    sc = s[q, c]
    order = np.argsort(-sc)
    keep = []
    for cls in np.unique(c):
        idx = [i for i in order if c[i] == cls]
        while idx:
            i = idx.pop(0)
            keep.append(i)
            if idx:
                ov = iou_one(boxes[i], boxes[idx])
                idx = [j for j, o in zip(idx, ov) if o <= nms_iou]
    keep = sorted(keep, key=lambda i: -sc[i])[:max_det]
    return boxes[keep] * np.array([w, h, w, h]), sc[keep], c[keep]


def coco_eval(preds, ids, gt):
    from pycocotools.cocoeval import COCOeval
    if not preds:
        return {"AP": 0.0, "AP50": 0.0, "AP75": 0.0}
    ev = COCOeval(gt, gt.loadRes(preds), "bbox")
    ev.params.imgIds = [int(i) for i in ids]
    import contextlib
    import io
    with contextlib.redirect_stdout(io.StringIO()):
        ev.evaluate(), ev.accumulate(), ev.summarize()
    return {"AP": float(ev.stats[0]), "AP50": float(ev.stats[1]), "AP75": float(ev.stats[2])}


def prf(det_by_img, gt_by_img, iou_thr=0.5):
    """Precision/exhaustividad a IoU 0,5 con clase, emparejamiento voraz por puntuacion."""
    tp = fp = fn = 0
    for iid, gts in gt_by_img.items():
        dets = det_by_img.get(iid, [])
        used = set()
        for box, sc, cl in sorted(dets, key=lambda d: -d[1]):
            best, bj = iou_thr, -1
            for j, (gb, gc) in enumerate(gts):
                if j in used or gc != cl:
                    continue
                o = iou_one(np.array(box), np.array([gb]))[0]
                if o >= best:
                    best, bj = o, j
            if bj >= 0:
                used.add(bj), None
                tp += 1
            else:
                fp += 1
        fn += len(gts) - len(used)
    p, r = tp / max(tp + fp, 1), tp / max(tp + fn, 1)
    return {"P": p, "R": r, "F1": 2 * p * r / max(p + r, 1e-9), "TP": tp, "FP": fp, "FN": fn}


def to_coco(boxes, sc, cl, iid):
    return [{"image_id": int(iid), "category_id": int(c), "score": float(s),
             "bbox": [float(b[0]), float(b[1]), float(b[2] - b[0]), float(b[3] - b[1])]}
            for b, s, c in zip(boxes, sc, cl)]


def cmd_score(a):
    from pycocotools.coco import COCO
    import contextlib
    import io
    with contextlib.redirect_stdout(io.StringIO()):
        gt = COCO(str(VAL))
    thrs = [float(t) for t in a.thresholds.split(",")]
    summary = {}
    for path in a.raw:
        d = np.load(path, allow_pickle=True)
        ids = d["ids"]
        gt_by_img = {int(i): [([x, y, x + w, y + h], an["category_id"])
                              for an in gt.imgToAnns[int(i)] for x, y, w, h in [an["bbox"]]] for i in ids}
        res = {"prep": str(d["prep"]), "n_img": len(ids)}
        preds = []
        for k, iid in enumerate(ids):
            w, h = d["sizes"][k]
            preds += to_coco(*post_torch(d["logits"][k], d["boxes"][k], w, h), iid)
        res["torch"] = coco_eval(preds, ids, gt)
        res["app"] = {}
        for t in thrs:
            preds, det = [], {}
            for k, iid in enumerate(ids):
                w, h = d["sizes"][k]
                b, s, c = post_app(d["logits"][k], d["boxes"][k], w, h, t)
                preds += to_coco(b, s, c, iid)
                det[int(iid)] = list(zip(b.tolist(), s.tolist(), c.tolist()))
            res["app"][f"{t:.2f}"] = {**coco_eval(preds, ids, gt), **prf(det, gt_by_img), "n_det": len(preds)}
        summary[os.path.basename(path)] = res
        print(os.path.basename(path), "torch:", {k: round(100 * v, 2) for k, v in res["torch"].items()})
        for t, r in res["app"].items():
            print(f"  app thr={t} AP={100 * r['AP']:.2f} AP50={100 * r['AP50']:.2f} P={r['P']:.3f} R={r['R']:.3f} F1={r['F1']:.3f}")
    if a.json:
        json.dump(summary, open(a.json, "w"), indent=1)


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--backend", choices=["tflite", "onnx", "pt"], required=True)
    r.add_argument("--model")
    r.add_argument("--config")
    r.add_argument("--ckpt")
    r.add_argument("--prep", default="android", choices=["android", "android_area", "area", "pil"])
    r.add_argument("--raw", required=True)
    r.add_argument("--n", type=int, default=0, help="0 = todo VAL")
    r.add_argument("--threads", type=int, default=6)
    s = sub.add_parser("score")
    s.add_argument("--raw", nargs="+", required=True)
    s.add_argument("--thresholds", default="0.2,0.25,0.3,0.35,0.4,0.45,0.5,0.55,0.6,0.7")
    s.add_argument("--json")
    a = ap.parse_args()
    (cmd_run if a.cmd == "run" else cmd_score)(a)


if __name__ == "__main__":
    main()
