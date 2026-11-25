# On-device benchmarks

Measured with the app's **Speed & memory** screen (v1.0 APK, phone unplugged). Vision: 20-run speed test plus a warm-up loop; the median is reported. Power = battery current × voltage from Android `BatteryManager`, sampled every 200 ms: 3 s idle baseline (screen on), then at least 6 s of repeated diagnoses. Energy per scan = (scan power − idle power) × time per scan. Llama: one advice answer.

| Measure | Moto G34 5G | OPPO CPH2889 |
|---|---|---|
| SoC · cores · RAM | Qualcomm Snapdragon 695 (SM6375) · 8 · 7.5 GB | MediaTek MT6835 · 8 · 5.6 GB |
| Android | 15 | 16 |
| Vision models load (disease v4 INT8 + leaf-photo check FP32) | 163 ms | 132 ms |
| Diagnosis: leaf-photo check + disease model, median (min) | **72 ms** (61), 81 runs | **75 ms** (64), 79 runs |
| Leaf + spot analysis, median | 83 ms | 106 ms |
| One real photo, end to end | 908 ms (decode 71 · classify 106 · severity 608 · quality 68) | 504 ms (decode 101 · classify 128 · severity 184 · quality 15) |
| Llama 3.2 1B (8da4w) load | 3,055 ms | 5,746 ms |
| Llama prefill | 61.8 tokens/s | 58.9 tokens/s |
| Llama decode | **9.1 tokens/s** | **8.4 tokens/s** |
| Time to first token | 5,546 ms (342-token prompt) | 8,797 ms (518-token prompt) |
| One answer | 328 tokens in 41.4 s | 35 tokens in 12.8 s |
| App memory (PSS), vision only | 159 MB (peak 168 MB) | — |
| App memory (PSS), with Llama | 1,005 MB (peak 1,021 MB) | 984 MB (peak 1,021 MB) |
| Power: idle → during scans | 2,559 mW → 5,446 mW | not valid* |
| Energy per scan above idle | **207.9 mJ** | not valid* |
| Llama answer: average power · energy | 5,133 mW · 212.8 J (41.5 s) | not valid* |

Notes
- Moto G34 power figures are indicative: a voice call was active during the measurement, which raises the idle baseline.
- \*The OPPO's battery gauge reports current in a non-standard unit (readings ≈ 2 mW, about 1,000× too low), so its power numbers are not used.

## Notebook CPU (Kaggle, x86, reference only)

| Model | ms per photo |
|---|---|
| v3 MobileNetV3-Large INT8, 224 px | 7.8 |
| v4 EfficientNet-B2 INT8, 288 px (one pass) | ~37 (74 with mirror averaging) |
