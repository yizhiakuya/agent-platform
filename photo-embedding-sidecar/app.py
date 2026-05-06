import base64
import io
import os
import threading
from typing import Any

import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from PIL import Image
from sentence_transformers import SentenceTransformer


MODEL_NAME = os.getenv("PHOTO_EMBEDDING_MODEL", "clip-ViT-B-32-multilingual-v1")
# The multilingual text model was trained to project text into the same
# embedding space as the image model below.
TEXT_MODEL_NAME = os.getenv("PHOTO_EMBEDDING_TEXT_MODEL", "sentence-transformers/clip-ViT-B-32-multilingual-v1")
IMAGE_MODEL_NAME = os.getenv("PHOTO_EMBEDDING_IMAGE_MODEL", "sentence-transformers/clip-ViT-B-32")
DIM = int(os.getenv("PHOTO_EMBEDDING_DIM", "1024"))
DEVICE = os.getenv("PHOTO_EMBEDDING_DEVICE", "cpu")

app = FastAPI(title="Agent Platform Photo Embedding Sidecar")
text_model: SentenceTransformer | None = None
image_model: SentenceTransformer | None = None
model_lock = threading.Lock()


class EmbeddingRequest(BaseModel):
    model: str | None = None
    input: str | dict[str, Any] | list[str | dict[str, Any]]
    task: str | None = None


def get_text_model() -> SentenceTransformer:
    global text_model
    if text_model is None:
        with model_lock:
            if text_model is None:
                text_model = SentenceTransformer(TEXT_MODEL_NAME, device=DEVICE)
    return text_model


def get_image_model() -> SentenceTransformer:
    global image_model
    if image_model is None:
        with model_lock:
            if image_model is None:
                image_model = SentenceTransformer(IMAGE_MODEL_NAME, device=DEVICE)
    return image_model


def as_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else [value]


def decode_image(value: str) -> Image.Image:
    if value.startswith("data:"):
        value = value.split(",", 1)[1]
    try:
        raw = base64.b64decode(value, validate=False)
        image = Image.open(io.BytesIO(raw)).convert("RGB")
        if image.width < 32 or image.height < 32:
            image = image.resize((max(32, image.width), max(32, image.height)))
        return image
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"bad image input: {exc}") from exc


def normalize_item(item: str | dict[str, Any]) -> tuple[str, str | Image.Image]:
    if isinstance(item, str):
        return ("text", item)
    if "text" in item:
        return ("text", str(item.get("text") or ""))
    if "image" in item:
        return ("image", decode_image(str(item.get("image") or "")))
    if "bytes" in item:
        return ("image", decode_image(str(item.get("bytes") or "")))
    raise HTTPException(status_code=400, detail="input item must contain text, image, or bytes")


def to_dim(vector: np.ndarray) -> list[float]:
    values = vector.astype(np.float32).tolist()
    if DIM > 0 and len(values) > DIM:
        return values[:DIM]
    if DIM > 0 and len(values) < DIM:
        values.extend([0.0] * (DIM - len(values)))
    return values


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "status": "ok",
        "model": MODEL_NAME,
        "text_model": TEXT_MODEL_NAME,
        "image_model": IMAGE_MODEL_NAME,
        "dim": DIM,
    }


@app.post("/v1/embeddings")
def embeddings(req: EmbeddingRequest) -> dict[str, Any]:
    items = [normalize_item(item) for item in as_list(req.input)]
    data = []
    for index, (kind, payload) in enumerate(items):
        if kind == "image":
            st = get_image_model()
            vec = st.encode(payload, convert_to_numpy=True, normalize_embeddings=True)
        else:
            st = get_text_model()
            vec = st.encode(payload, convert_to_numpy=True, normalize_embeddings=True)
        data.append({
            "object": "embedding",
            "index": index,
            "embedding": to_dim(np.asarray(vec)),
        })
    return {
        "object": "list",
        "model": req.model or MODEL_NAME,
        "data": data,
        "usage": {"prompt_tokens": 0, "total_tokens": 0},
    }
