#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
MODEL_ROOT="$PROJECT_ROOT/ppocr-sdk/src/main/assets/models"
mkdir -p "$MODEL_ROOT/det" "$MODEL_ROOT/rec"

download_model() {
    local url=$1
    local destination=$2
    local expected_sha256=$3
    local temporary
    temporary=$(mktemp)
    trap 'rm -f "$temporary"' RETURN

    curl --fail --location --retry 4 --retry-all-errors --connect-timeout 20 \
        --output "$temporary" "$url"
    printf '%s  %s\n' "$expected_sha256" "$temporary" | sha256sum --check --status
    mv "$temporary" "$destination"
    trap - RETURN
}

download_model \
    "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/main/inference.onnx?download=true" \
    "$MODEL_ROOT/det/inference.onnx" \
    "d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e"

download_model \
    "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/main/inference.onnx?download=true" \
    "$MODEL_ROOT/rec/inference.onnx" \
    "5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634"

# The recognition YAML contains the model's character dictionary. Its checksum
# is pinned after the first CI download and is verified in the workflow.
curl --fail --location --retry 4 --retry-all-errors --connect-timeout 20 \
    --output "$MODEL_ROOT/rec/inference.yml" \
    "https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/35200ec2acf6562260fa500e3262f5ea11f33642/inference.yml?download=true"

test "$(wc -c < "$MODEL_ROOT/rec/inference.yml")" -ge 100000
sha256sum \
    "$MODEL_ROOT/det/inference.onnx" \
    "$MODEL_ROOT/rec/inference.onnx" \
    "$MODEL_ROOT/rec/inference.yml"
