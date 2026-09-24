"""FastAPI entrypoint for the AI service: agent chat, RAG, RL, LangGraph workflows and vision."""
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import PlainTextResponse
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver
from pydantic import BaseModel, Field

from app import agent, events, observability, rag, scm_client, vision
from app.config import get_settings
from app.rl import service as rl_service
from app.workflows import service as workflows

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s - %(message)s")
log = logging.getLogger(__name__)
observability.configure()


@asynccontextmanager
async def lifespan(_: FastAPI):
    db = get_settings().workflow_db_file
    db.parent.mkdir(parents=True, exist_ok=True)
    # Durable LangGraph checkpoints: workflows waiting for approval survive restarts.
    async with AsyncSqliteSaver.from_conn_string(str(db)) as checkpointer:
        workflows.init(checkpointer)
        await events.start()
        events.consume_alerts(workflows.on_alert)
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
    return {"status": "ok", "model": s.bedrock_model_id, "kafka": events.enabled(), "mcp": s.scm_mcp_url,
            "langsmith": observability.enabled()}


@app.post("/ai/chat")
async def chat(req: ChatRequest) -> dict:
    return await agent.chat(req.session_id, req.message)


class ChatFeedbackRequest(BaseModel):
    run_id: str
    score: float = Field(ge=0, le=1)  # 1 = helpful, 0 = not helpful
    comment: str = ""


@app.post("/ai/chat/feedback")
async def chat_feedback(req: ChatFeedbackRequest) -> dict:
    """Stores the user's rating of an assistant reply on its LangSmith trace."""
    return {"recorded": observability.feedback(req.run_id, "user_rating", req.score, req.comment)}


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


class StartWorkflowRequest(BaseModel):
    alert: dict


class DecisionRequest(BaseModel):
    approved: bool
    actions: list[dict] | None = None  # optional edited actions (e.g. a changed quantity)
    comment: str = ""


@app.get("/ai/workflows/exceptions")
async def list_exception_workflows() -> list[dict]:
    return await workflows.list_runs()


@app.post("/ai/workflows/exceptions")
async def start_exception_workflow(req: StartWorkflowRequest) -> dict:
    if not req.alert.get("alertId") or not req.alert.get("type"):
        raise HTTPException(status_code=400, detail="alert needs alertId and type")
    return await workflows.start(req.alert)


@app.get("/ai/workflows/exceptions/graph", response_class=PlainTextResponse)
async def exception_workflow_graph() -> str:
    """The workflow's LangGraph structure as a Mermaid diagram."""
    return workflows.mermaid()


@app.get("/ai/workflows/exceptions/{thread_id}")
async def get_exception_workflow(thread_id: str) -> dict:
    run = await workflows.view(thread_id)
    if run is None:
        raise HTTPException(status_code=404, detail=f"No workflow {thread_id}")
    return run


@app.post("/ai/workflows/exceptions/{thread_id}/decision")
async def decide_exception_workflow(thread_id: str, req: DecisionRequest) -> dict:
    try:
        return await workflows.decide(thread_id, req.approved, req.actions, req.comment)
    except KeyError:
        raise HTTPException(status_code=404, detail=f"No workflow {thread_id}")
    except ValueError as e:
        raise HTTPException(status_code=409, detail=str(e))


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
