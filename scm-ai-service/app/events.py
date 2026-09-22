"""Publishes events to Kafka in the shared SupplyChainEvent envelope."""
import json
import logging
import uuid
from datetime import datetime, timezone

from aiokafka import AIOKafkaProducer

from app.config import get_settings

log = logging.getLogger(__name__)

_producer: AIOKafkaProducer | None = None


async def start() -> None:
    global _producer
    s = get_settings()
    if not s.kafka_enabled:
        return
    _producer = AIOKafkaProducer(
        bootstrap_servers=s.kafka_bootstrap_servers,
        value_serializer=lambda v: json.dumps(v).encode(),
        key_serializer=lambda k: k.encode() if k else None,
    )
    await _producer.start()
    log.info("Kafka producer connected to %s", s.kafka_bootstrap_servers)


async def stop() -> None:
    if _producer:
        await _producer.stop()


def enabled() -> bool:
    return _producer is not None


def envelope(type_: str, entity_id: str, data: dict) -> dict:
    return {
        "eventId": str(uuid.uuid4()),
        "type": type_,
        "source": "scm-ai-service",
        "entityId": entity_id,
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "data": data,
    }


async def publish(topic: str, event: dict) -> None:
    if _producer is None:
        raise RuntimeError("Kafka is disabled")
    await _producer.send_and_wait(topic, event, key=event["entityId"])
