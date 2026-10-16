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
    # This AI service, as seen from the MCP server (which runs as its own process), for dock door monitors.
    scm_ai_url: str = "http://localhost:8000"
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
    # Cold chain: detector classes that signal a temperature abuse, and thermal-image thresholds.
    cold_chain_labels: str = "frost,ice,ice_crystals,condensation,thawed,mold,spoiled,leak,swollen"
    thermal_tolerance_c: float = 1.0  # degrees outside the profile before a pixel counts as a spot
    thermal_min_spot_pct: float = 0.5  # spots smaller than this share of the frame are ignored
    # Reefer dock door monitoring (video).
    dock_door_max_open_s: float = 120  # default limit before an opening counts as a violation
    dock_door_pixel_threshold: int = 30  # grey-level change for a door-region pixel to count as changed
    dock_door_open_fraction: float = 0.35  # share of the door region that must change for "open"
    dock_door_debounce_s: float = 2.0  # a state must hold this long, so passers-by are ignored
    dock_door_reference_rate: float = 0.02  # how fast the closed-door reference follows lighting changes
    dock_door_sample_fps: float = 5.0
    dock_door_max_upload_mb: int = 200
    dock_door_reconnect_s: float = 5.0
    dock_door_max_monitors: int = 16

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
