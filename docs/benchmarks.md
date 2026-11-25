# On-device benchmarks

Measured with the app's **Speed & memory** screen. The phone is unplugged; power = battery current × voltage, sampled every 200 ms. There's a 3 s idle baseline, then at least 6 s of repeated scans. For Llama, one advice answer is measured.

## Notebook CPU (Kaggle, x86, for reference only)

| Model | ms per photo |
|---|---|
| v3 MobileNetV3-Large INT8, 224 px | 7.8 |
| v4 EfficientNet-B2 INT8, 288 px (one pass) | ~37 (74 with mirror averaging) |

## Phones (Arm)

_To be filled from the app's "Copy results" on each phone._

| Phone | SoC | Vision load | Scan (gate + disease) | Leaf + spots | Llama load | Llama tokens/s | Vision power | Energy per scan | Llama energy per answer | App memory |
|---|---|---|---|---|---|---|---|---|---|---|
| OnePlus Nord 4 (8 GB) | Snapdragon 7+ Gen 3 | | | | | | | | | |
| Motorola Moto G34 (8 GB) | Snapdragon 695 | | | | | | | | | |
| OPPO CPH2889 | | | | | | | | | | |
