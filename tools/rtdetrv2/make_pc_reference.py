"""Referencia del PC para comparar con el telefono: cajas del TFLite FP16 con el posprocesado de la app en N imagenes de VAL.

Entrada: el .npz de `eval_tflite_val.py run --backend tflite --prep android` (todo VAL). Salida: carpeta con las imagenes
(JPEG reescalado a 2048 px de lado largo para ligerar el adb push) y pc_boxes.json con, por imagen, las cajas
(x1,y1,x2,y2 normalizadas 0-1, clase, puntuacion). Las imagenes se eligen sin mirar TEST: cada k-esima de VAL con >= 2 objetos.
"""
import argparse
import json
import os
import sys

import cv2
import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import eval_tflite_val as E  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--thr", type=float, default=0.6)
    ap.add_argument("--n", type=int, default=10)
    a = ap.parse_args()
    d = np.load(a.raw, allow_pickle=True)
    val = json.load(open(E.VAL, encoding="utf8"))
    imgs = {i["id"]: i for i in val["images"]}
    cnt = {}
    for an in val["annotations"]:
        cnt[an["image_id"]] = cnt.get(an["image_id"], 0) + 1
    cand = [k for k, i in enumerate(d["ids"]) if cnt.get(int(i), 0) >= 2]
    pick = cand[:: max(1, len(cand) // a.n)][: a.n]
    os.makedirs(os.path.join(a.out, "images"), exist_ok=True)
    root = E.data_root()
    ref = []
    for k in pick:
        im = imgs[int(d["ids"][k])]
        bgr = cv2.imread(os.path.join(root, im["file_name"]))
        h, w = bgr.shape[:2]
        s = 2048 / max(h, w)
        if s < 1:
            bgr = cv2.resize(bgr, (int(w * s), int(h * s)), interpolation=cv2.INTER_AREA)
        name = f"val_{im['id']}.jpg"
        cv2.imwrite(os.path.join(a.out, "images", name), bgr, [cv2.IMWRITE_JPEG_QUALITY, 92])
        # las cajas son normalizadas respecto de la imagen original; tras reescalar la proporcion no cambia
        b, sc, c = E.post_app(d["logits"][k], d["boxes"][k], 1.0, 1.0, a.thr)
        ref.append({"file": name, "orig": im["file_name"], "n_gt": cnt[int(im["id"])],
                    "detections": [{"box": [round(float(v), 4) for v in bb], "class": E_NAMES[int(cc)], "score": round(float(s_), 4)}
                                   for bb, s_, cc in zip(b, sc, c)]})
    json.dump({"threshold": a.thr, "note": "TFLite FP16 en el PC, preprocesado Android, posprocesado de la app; cajas normalizadas",
               "images": ref}, open(os.path.join(a.out, "pc_boxes.json"), "w"), indent=1)
    print(len(ref), "imagenes en", a.out)


E_NAMES = ["Bottle", "Can", "Fishing_Net", "Glove", "Mask", "Metal_Debris", "Plastic_Debris", "Tire"]

if __name__ == "__main__":
    main()
