"""Variantes INT8 del RT-DETRv2 «raw» para TFLite, a partir del SavedModel de onnx2tf (sin GPU).

  dynamic  pesos int8, activaciones float (rango dinamico). No necesita calibracion.
  full     INT8 completo (pesos y activaciones) con E/S float32, calibrado con imagenes reales de TRAIN.

Se ejecuta en la imagen rtdetr-convert (TensorFlow 2.19):
  docker run --rm -v <directorio_de_trabajo>:/work -v <este_dir>:/tools --entrypoint python3 rtdetr-convert \
      /tools/quantize_tflite_variants.py --saved-model /work/t2 --mode dynamic --out /work/int8/rtdetrv2_exp08s3_int8_dyn.tflite
La calibracion lee un .npy NHWC float32 [N,640,640,3] (--calib-npy) preparado con el preprocesado de la app.
"""
import argparse
import os

import numpy as np


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--saved-model", required=True)
    ap.add_argument("--mode", choices=["dynamic", "full"], required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--calib-npy")
    a = ap.parse_args()
    import tensorflow as tf
    conv = tf.lite.TFLiteConverter.from_saved_model(a.saved_model)
    conv.optimizations = [tf.lite.Optimize.DEFAULT]
    conv.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
    if a.mode == "full":
        calib = np.load(a.calib_npy, mmap_mode="r")

        def rep():
            for i in range(len(calib)):
                yield [np.asarray(calib[i:i + 1], dtype=np.float32)]
        conv.representative_dataset = rep
        conv.inference_input_type = tf.float32
        conv.inference_output_type = tf.float32
    model = conv.convert()
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    open(a.out, "wb").write(model)
    print("guardado", a.out, f"{len(model) / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
