from typing import List
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException
from psycopg.types.json import Jsonb

from ..auth import require_api_key
from ..db import get_conn
from ..schemas import PersonCreate, PersonMatch, PersonOut, PersonSearchRequest

router = APIRouter(
    prefix="/people",
    tags=["people"],
    dependencies=[Depends(require_api_key)],
)

_PERSON_FIELDS = list(PersonOut.model_fields.keys())


def _row_to_person(row: dict) -> PersonOut:
    return PersonOut(**{k: row[k] for k in _PERSON_FIELDS})


@router.post("", response_model=PersonOut, status_code=201)
def create_person(body: PersonCreate) -> PersonOut:
    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO people
                    (name, aliases, org, role, first_met_at, last_met_at,
                     face_embedding, notes_summary, metadata)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                RETURNING *
                """,
                (
                    body.name,
                    body.aliases,
                    body.org,
                    body.role,
                    body.first_met_at,
                    body.last_met_at,
                    body.face_embedding,
                    body.notes_summary,
                    Jsonb(body.metadata),
                ),
            )
            row = cur.fetchone()
        conn.commit()
    return _row_to_person(row)


@router.get("/{person_id}", response_model=PersonOut)
def get_person(person_id: UUID) -> PersonOut:
    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT * FROM people WHERE id = %s", (person_id,))
            row = cur.fetchone()
            if not row:
                raise HTTPException(404, "person not found")
    return _row_to_person(row)


@router.post("/search", response_model=List[PersonMatch])
def search_people(body: PersonSearchRequest) -> List[PersonMatch]:
    if body.face_embedding is None and not body.query:
        raise HTTPException(400, "either face_embedding or query is required")

    with get_conn() as conn:
        with conn.cursor() as cur:
            if body.face_embedding is not None:
                cur.execute(
                    """
                    SELECT *, 1 - (face_embedding <=> %s::vector) AS _score
                    FROM people
                    WHERE face_embedding IS NOT NULL
                    ORDER BY face_embedding <=> %s::vector
                    LIMIT %s
                    """,
                    (body.face_embedding, body.face_embedding, body.top_k),
                )
            else:
                like = f"%{body.query}%"
                cur.execute(
                    """
                    SELECT *, 1.0::float AS _score
                    FROM people
                    WHERE name ILIKE %s
                       OR EXISTS (SELECT 1 FROM unnest(aliases) a WHERE a ILIKE %s)
                       OR org ILIKE %s
                    LIMIT %s
                    """,
                    (like, like, like, body.top_k),
                )
            rows = cur.fetchall()

    return [
        PersonMatch(person=_row_to_person(r), score=float(r["_score"]))
        for r in rows
    ]
