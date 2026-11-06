# RT-DETRv2 final -> TFLite FP16 (v1.0.0)

Modelo: `exp08_real_synth_minor_s3` (R50vd, 150 consultas, 8 clases, pesos EMA de `best.pth`).

## Cadena
1. `export_onnx_raw.py` (dentro del contenedor `rt_detrv2`, CPU): ONNX sin posprocesador, entrada 1x3x640x640,
   salidas `pred_logits [1,150,8]` y `pred_boxes [1,150,4]` (cxcywh normalizado).
2. `python -m onnxsim raw_dyn.onnx raw.onnx --overwrite-input-shape images:1,3,640,640` (ONNX estatico; sin esto
   `onnx2tf` falla con Concat int32/int64 o Tile).
3. `convert_rtdetr.py` en la imagen `rtdetr-convert` (onnx2tf 2.0.21, TF 2.19). Dos ajustes respecto a la version
   de RT-DETRv2-Densea: `replace_to_pseudo_operators=["Erf","erf"]` (esa version solo reconoce «erf» en minusculas;
   con «Erf» deja un FlexErf) y `disable_strict_mode=True`. onnx2tf descarga un `.npy` de prueba de GitHub; sin red hay
   que dejar en el directorio de trabajo `calibration_image_sample_data_20x128x128x3_float32.npy`.
4. Resultado: FP16 86.842.488 B, FP32 172,6 MB; 0 operadores Flex/SELECT_TF_OPS. Las salidas salen con los nombres
   cruzados (`pred_logits` tiene forma [1,150,4]); la app detecta el orden por la forma, igual que antes.

## Validacion en VAL (2.185 imagenes, `eval_tflite_val.py`; nunca TEST)
Preprocesado de la app (lado largo a 640 bilineal + estiramiento a 640x640, RGB, /255). AP COCO, posprocesado de PyTorch (top-300, sin NMS):

| Modelo | AP | AP50 | AP75 |
|---|---|---|---|
| PyTorch FP32 (CPU) | 44,01 | 64,43 | 46,30 |
| ONNX FP32 | 44,01 | 64,43 | 46,30 |
| TFLite FP32 | 44,01 | 64,43 | 46,30 |
| TFLite FP16 | 43,90 | 64,22 | 46,24 |

TFLite FP16 = 99,75 % del AP de PyTorch (criterio >= 99 %: cumple). Sensibilidad al reescalado (ONNX FP32): android
44,01; android con INTER_AREA en la reduccion 43,99; area directo 43,56; PIL bilineal 43,80.

## Umbral de la app (posprocesado de la app: sigmoide, umbral, NMS por clase IoU 0,5, max. 100; TFLite FP16)
F1 a IoU 0,5 con clase: 0,45 -> 0,749; 0,50 -> 0,752; 0,55 -> 0,755; **0,60 -> 0,758** (P 0,831, R 0,696; AP 39,86);
0,70 -> 0,751. Se fija 0,6 (maximo de F1; meseta 0,55-0,65).

## INT8
- INT8 completo (calibrado con 100 imagenes de TRAIN, E/S float32): el interprete se cierra al asignar tensores en el PC
  con XNNPACK, (reproduce el fallo observado en el Exynos 2200). Descartado.
- INT8 de pesos (rango dinamico, 45,3 MB): carga, pero en el PC tarda ~56 s por imagen (no evaluable en VAL completo) y
  en 40 imagenes de VAL no es comparable de forma fiable (AP 37,7 frente a 29,9 de FP16 en ese subconjunto, ruido).
  No se incluye en la app.
- Se entrega solo FP16.

## Uso
`RT_VAL_JSON=<val.json> DATA_ROOT=<imagenes> python eval_tflite_val.py run --backend tflite --model m.tflite --prep android --raw out.npz`
y luego `python eval_tflite_val.py score --raw out.npz`. `make_pc_reference.py` genera `dist/pc_reference`
(10 imagenes de VAL y sus cajas en el PC) para compararlas con el telefono con `tools/measure_s22.ps1`.
