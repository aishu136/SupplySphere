"""LangSmith: tracing, human feedback and evaluation for the AI features.

Opt-in. Nothing is sent unless LANGSMITH_TRACING=true and LANGSMITH_API_KEY are set; then every
LangChain/LangGraph run (chat agent, MCP tool calls, exception workflow, RAG, Claude calls) is traced
automatically, and the @traceable functions (RL, vision, retrieval) join those traces.
Traces contain prompts, retrieved documents and supply chain data; set LANGSMITH_HIDE_INPUTS /
LANGSMITH_HIDE_OUTPUTS=true to redact them.
"""
import logging
import os

from langsmith import Client
from langsmith import utils as langsmith_utils
from langsmith.run_helpers import get_current_run_tree
from langsmith.utils import tracing_is_enabled

from app.config import get_settings

log = logging.getLogger(__name__)


def configure() -> None:
    """Exports LangSmith settings from .env to the process environment, where the SDK reads them."""
    s = get_settings()
    for key, value in {
        "LANGSMITH_TRACING": "true" if s.langsmith_tracing else None,
        "LANGSMITH_API_KEY": s.langsmith_api_key,
        "LANGSMITH_PROJECT": s.langsmith_project,
        "LANGSMITH_ENDPOINT": s.langsmith_endpoint,
    }.items():
        if value:
            os.environ.setdefault(key, value)
    refresh()
    if enabled():
        log.info("LangSmith tracing on (project %s)", os.environ.get("LANGSMITH_PROJECT", "default"))


def refresh() -> None:
    """The SDK memoizes environment lookups; clear them so newly exported settings take effect."""
    langsmith_utils.get_env_var.cache_clear()


def enabled() -> bool:
    return bool(tracing_is_enabled()) and bool(os.environ.get("LANGSMITH_API_KEY"))


def current_run_id() -> str | None:
    """ID of the LangSmith run executing right now (e.g. a LangGraph node), if tracing is on."""
    run = get_current_run_tree()
    return str(run.id) if run else None


def feedback(run_id: str | None, key: str, score: float, comment: str | None = None) -> bool:
    """Attaches a human judgement to a traced run. Returns False (no-op) when tracing is off."""
    if not run_id or not enabled():
        return False
    try:
        Client().create_feedback(run_id, key=key, score=score, comment=comment or None)
        return True
    except Exception as e:  # feedback must never break the business action it describes
        log.warning("LangSmith feedback %s for run %s failed: %s", key, run_id, e)
        return False


def run_config(name: str, tags: list[str], metadata: dict, **extra) -> dict:
    """LangChain RunnableConfig fields that name and label a trace for filtering in LangSmith."""
    return {"run_name": name, "tags": ["supplysphere", *tags], "metadata": metadata, **extra}
