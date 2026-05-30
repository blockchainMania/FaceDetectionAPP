# Jarvis Memory API

Personal memory & CRM API for the Jarvis project — visual memory + voice AI for
smart glasses. Phase 1 (this repo) is the **server**: stores people / meetings /
needs / episodic memories with vector search.

## Stack

- **FastAPI** (Python 3.11+)
- **Postgres + pgvector** (via Docker)
- **Embeddings** in-process, CPU:
  - default `nlpai-lab/KURE-v1` (Korean-focused, BGE-M3 한국어 파인튜닝)
  - swap to `BAAI/bge-m3` via `.env` (same 1024-dim → no schema change)
- **No external API calls** — runs $0 in API costs.

## Quick start

```bash
# 1. Start DB
docker compose up -d

# 2. Python deps  (first time: ~3GB — sentence-transformers pulls torch)
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

# 3. Env
cp .env.example .env  # edit API_KEY before exposing

# 4. Seed dummy data
#    First run downloads the embedding model (~2GB, ~1-2min on a decent connection)
python scripts/seed.py

# 5. Run API
uvicorn app.main:app --reload --port 8000

# 6. Hit the 4 target commands
bash test_commands.sh
```

Interactive docs: <http://localhost:8000/docs>

## Endpoints

| Method | Path                            | Purpose |
|--------|---------------------------------|---------|
| POST   | `/people`                       | Create person (optionally with face_embedding) |
| GET    | `/people/{id}`                  | Get person |
| POST   | `/people/search`                | Search by face_embedding OR name/alias/org text |
| POST   | `/meetings`                     | Create meeting (summary auto-embedded) |
| GET    | `/meetings/{id}`                | Get meeting |
| POST   | `/meetings/search`              | Vector search meetings (+ time / person filters) |
| POST   | `/memory/save`                  | Save episodic memory |
| POST   | `/memory/search`                | Vector search memories (+ time / person filters) |
| POST   | `/needs`                        | Save a need (extracted from meeting transcript) |
| GET    | `/needs/by-person/{id}`         | Needs for a person |
| GET    | `/people/{id}/context`          | Bundle (person + needs + recent meetings) for proposal synthesis |
| GET    | `/health`                       | Liveness + model info |

All endpoints require `X-API-Key` header.

## Maps to the 4 target commands

| User says                                          | Endpoint flow |
|----------------------------------------------------|---------------|
| "방금 만난 사람 저장해줘"                          | `POST /people` + `POST /meetings` |
| "이 사람 전에 어디서 만났지?"                      | `POST /people/search` (face) → `GET /people/{id}` |
| "지난번 배터리 부품사 미팅에서 나온 니즈?"         | `POST /meetings/search` → `GET /needs/by-person/{id}` |
| "이 사람이 관심 있어 할 제안 포인트?"              | `GET /people/{id}/context` → client Gemini synthesizes |

## Embedding model — swap & compare

Default = KURE-v1 (Korean meeting content). To try BGE-M3:

```
EMBEDDING_MODEL=BAAI/bge-m3   # .env
```

Re-seed (`python scripts/seed.py`) for apples-to-apples retrieval comparison.
Quick A/B on a Korean test set without touching the DB:

```bash
python scripts/eval_embeddings.py
```

## Why this shape

- **No server-side LLM.** Proposal points etc. are synthesized by the *client*
  (Gemini Live) using context returned by `/people/{id}/context`.
  Server stays cheap, deterministic, and offline-friendly.
- **Face embeddings come from the phone.** FaceDetectionAPP's TFLite (FaceNet
  / MobileFaceNet) already outputs them. Server only stores and searches —
  it does not run a face model.
- **OpenClaw skipped.** Gemini Live calls these endpoints directly via
  function-calling. Add OpenClaw later only when external-app actions
  (messaging, calendar, web search) are needed — not for memory itself.

## Connecting from VisionClaw

In VisionClaw's `GeminiConfig`, declare these as functions (instead of one
`execute` tool):

```
save_person(name, org, role, face_embedding?)
search_people(face_embedding|query, top_k)
save_meeting(title, person_ids, started_at, summary, transcript?)
save_memory(text, captured_at, related_person_ids?, source)
search_memory(query, top_k, time_from?, time_to?, person_id?)
search_meetings(query, top_k, time_from?, time_to?, person_id?)
save_need(person_id, meeting_id?, text, category)
get_proposal_context(person_id)
```

Each maps 1:1 to an endpoint above.

## Next milestones

- [ ] VisionClaw `ToolCallRouter` HTTP client to these endpoints
- [ ] Gemini Live function declarations
- [ ] Client-side: meeting transcript → needs auto-extraction (Gemini Flash)
- [ ] Phase 3: visual memory (SigLIP 2 / jina-clip-v2) for "그 빨간 책" 검색
- [ ] Production: TLS, key rotation, RLS, backups
