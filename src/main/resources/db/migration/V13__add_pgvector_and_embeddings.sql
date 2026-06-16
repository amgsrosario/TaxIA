CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE knowledge_case_embeddings (
    id UUID PRIMARY KEY,
    knowledge_case_id UUID NOT NULL REFERENCES knowledge_cases(id),
    chunk_text TEXT NOT NULL,
    embedding vector(768) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_knowledge_case_embeddings_case UNIQUE (knowledge_case_id)
);

CREATE INDEX idx_knowledge_case_embeddings_vector
    ON knowledge_case_embeddings
    USING hnsw (embedding vector_cosine_ops);
