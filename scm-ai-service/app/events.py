"""Kafka I/O: publishes events in the shared SupplyChainEvent envelope, and consumes alerts."""
import asyncio
import json
import logging
import uuid
from collections.abc import Awaitable, Callable
from datetime import datetime, timezone

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

from app.config import get_settings

log = logging.getLogger(__name__)

_producer: AIOKafkaProducer | None = None
_consumer_task: asyncio.Task | None = None


def consume_alerts(handler: Callable[[dict], Awaitable[None]]) -> None:
    """Runs handler for every message on scm.alerts in a background task (no-op if Kafka is off).
    One consumer group for all replicas: each alert is handled once."""
    global _consumer_task
    s = get_settings()
    if not s.kafka_enabled:
        return

    async def run() -> None:
        consumer = AIOKafkaConsumer(s.kafka_alerts_topic, bootstrap_servers=s.kafka_bootstrap_servers,
                                    group_id="scm-ai-workflows", auto_offset_reset="latest",
                                    value_deserializer=lambda v: json.loads(v))
        await consumer.start()
        log.info("Consuming %s for exception workflows", s.kafka_alerts_topic)
        try:
            async for message in consumer:
                await handler(message.value)
        finally:
            await consumer.stop()

    _consumer_task = asyncio.create_task(run())


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
    if _consumer_task:
        _consumer_task.cancel()
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
