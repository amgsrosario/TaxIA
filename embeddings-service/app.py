import os

from fastapi import FastAPI
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer

MODEL_NAME = os.environ.get("MODEL_NAME", "intfloat/multilingual-e5-base")

app = FastAPI()
model = SentenceTransformer(MODEL_NAME)


class EmbedRequest(BaseModel):
    texts: list[str]
    is_query: bool = False


class EmbedResponse(BaseModel):
    embeddings: list[list[float]]


@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_NAME}


@app.post("/embed", response_model=EmbedResponse)
def embed(request: EmbedRequest):
    # e5 models expect a "query: " / "passage: " prefix for best retrieval quality.
    prefix = "query: " if request.is_query else "passage: "
    prefixed = [f"{prefix}{t}" for t in request.texts]
    vectors = model.encode(prefixed, normalize_embeddings=True)
    return EmbedResponse(embeddings=vectors.tolist())
