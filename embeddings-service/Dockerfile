FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py .

# Warm the model into the image so first request isn't slow and to avoid
# downloading it again on every container restart.
ENV MODEL_NAME=intfloat/multilingual-e5-base
RUN python -c "from sentence_transformers import SentenceTransformer; import os; SentenceTransformer(os.environ['MODEL_NAME'])"

EXPOSE 8000

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
