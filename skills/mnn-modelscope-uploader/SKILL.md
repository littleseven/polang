---
name: mnn-modelscope-uploader
description: Use when converting a pre-trained model to MNN format and publishing it to a ModelScope repository for Android download
---

# MNN + ModelScope Model Uploader

## Overview

A repeatable workflow to take a source model (ONNX, PyTorch, etc.), convert it to Alibaba MNN format using a local MNN build, and publish it to ModelScope so the Android app can download it via `LlmModelDownloadManager`.

## When to Use

- A new face/vision/NLP model needs to run on Android via MNN
- The source model is available as ONNX, PyTorch, TFLite, Caffe, or TorchScript
- Local MNN source is already cloned and built at `~/code/MNN`
- The target repository on ModelScope already exists (empty is fine)

## When NOT to Use

- The model is already in MNN format on ModelScope or HuggingFace
- The runtime will use ONNX Runtime / NCNN / TFLite instead of MNN
- You do not have write access to the target ModelScope namespace

## Prerequisites

| Item | Path / Command | Check |
|---|---|---|
| MNN source + build | `~/code/MNN/build/MNNConvert` | `~/code/MNN/build/MNNConvert --version` |
| ModelScope CLI | `ms` | `ms --version` |
| Git LFS | `git-lfs` | `git lfs version` |
| Target repo created | `https://www.modelscope.cn/models/{namespace}/{repo}` | open in browser |
| Access Token | from https://www.modelscope.cn/my/myaccesstoken | keep ready |

## Workflow

### 1. Prepare workspace

```bash
export MODEL_NAME="arcface_r100"
export SRC_DIR="~/code/${MODEL_NAME}"
export MS_DIR="~/code/{namespace}_${MODEL_NAME}"
mkdir -p "${SRC_DIR}" "${MS_DIR}"
```

### 2. Obtain source model

**From HuggingFace:**

```bash
huggingface-cli download {owner}/{repo} {filename} --local-dir "${SRC_DIR}"
```

**From OpenVINO / direct URL:**

```bash
cd "${SRC_DIR}"
curl -LO {direct_download_url}
```

### 3. Convert to MNN

```bash
~/code/MNN/build/MNNConvert \
  -f ONNX \
  --modelFile "${SRC_DIR}/{model}.onnx" \
  --MNNModel "${SRC_DIR}/${MODEL_NAME}.mnn" \
  --bizCode MNN
```

Supported `-f` values: `ONNX`, `TORCH`, `TF`, `TFLITE`, `CAFFE`.

Inspect conversion output for `inputTensors` and `outputTensors` names; these are required in Android code.

### 4. Prepare ModelScope repo contents

```bash
cd "${MS_DIR}"
git init
git lfs install
git lfs track "*.mnn"
cp "${SRC_DIR}/${MODEL_NAME}.mnn" .
```

Create `README.md` with:

- Source model and conversion tool
- Input/output names and shapes
- Preprocessing (mean/std, channel order)
- License

### 5. Upload

```bash
ms upload {namespace}/{repo} "${MS_DIR}" \
  --token {MODELSCOPE_TOKEN} \
  --exclude ".git/**" \
  --commit-message "Add ${MODEL_NAME} MNN model"
```

### 6. Verify

```bash
curl -s "https://modelscope.cn/api/v1/models/{namespace}/{repo}/repo/files?Revision=master" \
  | python3 -m json.tool
```

Confirm the `.mnn` file is listed with `"IsLFS": true`.

### 7. Wire into Android app

1. Add entry to `app/src/main/res/raw/llm_models.json`
2. Register file list in `LlmModelDownloadManager.getModelFiles()`
3. Update model loading code to use new `modelId` and filename
4. Pass correct `inputName` / `outputName` to `MnnEmbeddingExtractor.initialize()`
5. Run `./gradlew :app:compileDebugKotlin`

## Quick Reference

| Step | Command |
|---|---|
| Convert ONNX → MNN | `~/code/MNN/build/MNNConvert -f ONNX --modelFile x.onnx --MNNModel x.mnn --bizCode MNN` |
| Login to ModelScope CLI | `ms login --token <TOKEN>` |
| Upload folder | `ms upload namespace/repo ./local-dir --token <TOKEN> --exclude ".git/**"` |
| Verify files | `curl https://modelscope.cn/api/v1/models/{namespace}/{repo}/repo/files?Revision=master` |

## Common Mistakes

| Mistake | Fix |
|---|---|
| Git push prompts for password | Use `ms upload` CLI instead of raw git; it handles LFS and auth |
| `--overwrite` not recognized | Remove it; `ms upload` overwrites by default if file changed |
| `.git` directory uploaded | Pass `--exclude ".git/**"` |
| Android fails to load MNN | Confirm `inputName` / `outputName` match conversion output exactly |
| Model too large for mobile | Consider MNN INT8 quantization before upload |
| Upload succeeds but download 404 | Wait 30–60 seconds for ModelScope CDN refresh |

## Android Config Template

```json
{
  "id": "{module}-{model}-mnn",
  "name": "{Model} MNN",
  "type": "FACE_EMBEDDING",
  "size": 260572332,
  "sources": { "ModelScope": "{namespace}/{repo}" },
  "files": ["{model}.mnn"],
  "tags": ["must-have", "face", "embedding", "mnn"]
}
```
