from typing import List

from fastapi import APIRouter, Depends
from psycopg.types.json import Jsonb

from ..auth import require_api_key
from ..db import get_conn
from ..embeddings import embed_text
from ..schemas import MemoryCreate, MemoryMatch, MemoryOut, MemorySearchRequest

router = APIRouter(
    prefix="/memory",
    tags=["memory"],
    dependencies=[Depends(require_api_key)],
)

_MEMORY_FIELDS = list(MemoryOut.model_fields.keys())


def _row_to_memory(row: dict) -> MemoryOut:
    return MemoryOut(**{k: row[k] for k in _MEMORY_FIELDS})


@router.post("/save", response_model=MemoryOut, status_code=201)
def save_memory(body: MemoryCreate) -> MemoryOut:
    embedding = embed_text(body.text)
    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO memories
                    (captured_at, text, embedding, related_person_ids,
                     related_meeting_id, source, metadata)
                VALUES (%s, %s, %s, %s, %s, %s, %s)
                RETURNING *
                """,
                (
                    body.captured_at,
                    body.text,
                    embedding,
                    body.related_person_ids,
                    body.related_meeting_id,
                    body.source,
                    Jsonb(body.metadata),
                ),
            )
            row = cur.fetchone()
        conn.commit()
    return _row_to_memory(row)


@router.post("/search", response_model=List[MemoryMatch])
def search_memories(body: MemorySearchRequest) -> List[MemoryMatch]:
    qvec = embed_text(body.query)
    sql = [
        "SELECT *, 1 - (embedding <=> %s::vector) AS _score",
        "FROM memories",
        "WHERE embedding IS NOT NULL",
    ]
    params: list = [qvec]
    if body.time_from:
        sql.append("AND captured_at >= %s"); params.append(body.time_from)
    if body.time_to:
        sql.append("AND captured_at <= %s"); params.append(body.time_to)
    if body.person_id:
        sql.append("AND %s = ANY(related_person_ids)"); params.append(body.person_id)
    sql.append("ORDER BY embedding <=> %s::vector LIMIT %s")
    params.extend([qvec, body.top_k])

    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute("\n".join(sql), params)
            rows = cur.fetchall()

    return [
        MemoryMatch(memory=_row_to_memory(r), score=float(r["_score"]))
        for r in rows
    ]
