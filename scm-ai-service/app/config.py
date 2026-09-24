from functools import lru_cache
from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

BASE_DIR = Path(__file__).resolve().parent.parent


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=BASE_DIR / ".env", extra="ignore")

    aws_region: str = "us-east-1"
    bedrock_model_id: str = "anthropic.claude-opus-5"
    bedrock_embedding_model_id: str = "amazon.titan-embed-text-v2:0"

    scm_api_url: str = "http://localhost:8080"
    scm_mcp_url: str = "http://localhost:8001/mcp"
    mcp_host: str = "0.0.0.0"
    mcp_port: int = 8001

    knowledge_base_dir: Path = BASE_DIR / "knowledge_base"
    vector_index_file: Path = BASE_DIR / "data" / "vector_index.json"

    kafka_enabled: bool = False
    kafka_bootstrap_servers: str = "localhost:9092"
    kafka_vision_topic: str = "scm.vision.events"
    kafka_alerts_topic: str = "scm.alerts"

    workflow_db_file: Path = BASE_DIR / "data" / "workflows.sqlite"
    # Alerts older than this (e.g. retained history on first deploy) do not start workflows.
    workflow_max_alert_age_minutes: int = 60

    yolo_model: str = "yolo11n.pt"
    vision_confidence: float = 0.35
    damage_labels: str = "damaged,damage,dent,tear,torn,crushed,wet,broken,hole"

    # LangSmith (opt-in). The standard LANGSMITH_* environment variables work too.
    langsmith_tracing: bool = False
    langsmith_api_key: str | None = None
    langsmith_project: str = "supplysphere"
    langsmith_endpoint: str | None = None

    rl_policy_file: Path = BASE_DIR / "data" / "rl_policies.json"
    rl_train_iterations: int = 20


@lru_cache
def get_settings() -> Settings:
    return Settings()
