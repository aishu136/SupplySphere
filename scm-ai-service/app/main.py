"""FastAPI entrypoint for the AI service: agent chat, RAG and computer-vision inspection."""
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from pydantic import BaseModel, Field

from app import agent, events, rag, scm_client, vision
from app.config import get_settings
from app.rl import service as rl_service

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")
log = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_: FastAPI):
    await events.start()
    yield
    await events.stop()


app = FastAPI(title="SCM AI Service", lifespan=lifespan)


class ChatRequest(BaseModel):
    session_id: str
    message: str


class RagRequest(BaseModel):
    question: str


@app.get("/ai/health")
async def health() -> dict:
    s = get_settings()
    return {"status": "ok", "model": s.bedrock_model_id, "kafka": events.enabled(), "mcp": s.scm_mcp_url}


@app.post("/ai/chat")
async def chat(req: ChatRequest) -> dict:
    return await agent.chat(req.session_id, req.message)


@app.post("/ai/rag/query")
async def rag_query(req: RagRequest) -> dict:
    return await rag.answer(req.question)


@app.post("/ai/rag/reindex")
async def rag_reindex() -> dict:
    store = await rag.get_store(rebuild=True)
    return {"chunks": len(store.store)}


class TrainRequest(BaseModel):
    iterations: int = Field(default=20, ge=5, le=200)


@app.post("/ai/rl/train")
async def rl_train(req: TrainRequest) -> dict:
    policies = await rl_service.train_all(req.iterations)
    return {key: {k: p[k] for k in ("policy", "rl", "baseline", "learning_curve")} for key, p in policies.items()}


@app.get("/ai/rl/recommendations")
async def rl_recommendations() -> list[dict]:
    return await rl_service.recommendations()


@app.post("/ai/vision/inspect")
async def inspect(
    file: UploadFile = File(...),
    tracking_number: str | None = Form(None),
    use_llm: bool = Form(False),
) -> dict:
    data = await file.read()
    try:
        result = await vision.inspect(data, file.content_type or "image/jpeg", use_llm)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))

    if tracking_number:
        await _report_inspection(tracking_number, result)
    return result.model_dump()


async def _report_inspection(tracking_number: str, result: vision.InspectionResult) -> None:
    """Hand the result to the core service: via Kafka (Camel routes it) or directly over REST."""
    if events.enabled():
        await events.publish(get_settings().kafka_vision_topic, events.envelope(
            "INSPECTION_COMPLETED", tracking_number,
            {"damaged": result.damaged, "summary": result.summary,
             "itemCounts": result.item_counts, "codes": result.codes},
        ))
    elif result.damaged:
        await scm_client.update_shipment_status(tracking_number, "DAMAGED")
        await scm_client.raise_alert("SHIPMENT_DAMAGED", "CRITICAL",
                                     f"Vision inspection flagged damage on {tracking_number}: {result.summary}",
                                     tracking_number)


@app.exception_handler(scm_client.ScmApiError)
async def scm_error(_, exc: scm_client.ScmApiError):
    from fastapi.responses import JSONResponse

    return JSONResponse(status_code=502, content={"detail": str(exc)})
