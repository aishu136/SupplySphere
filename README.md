# Supply Chain Management System

[![CI](https://github.com/aishu136/SupplySphere/actions/workflows/ci.yml/badge.svg)](https://github.com/aishu136/SupplySphere/actions/workflows/ci.yml)

An event-driven supply chain control tower. It tracks inventory, purchase orders and shipments, detects
problems in real time, and gives operators an AI copilot plus computer-vision dock inspection.

```
                         ┌───────────────────────────── Angular UI (scm-ui) ─────────────────────────────┐
                         │ Dashboard · Inventory · POs · Shipments · RL · AI assistant · Vision          │
                         └───────────────┬───────────────────────────────────────┬───────────────────────┘
                                   /api (REST + SSE)                           /ai
                                         │                                       │
 supplier CSV ──► Apache Camel ──► ┌─────▼──────────────┐    REST     ┌──────────▼───────────────────────┐
 drop folder      (file→csv→split) │ scm-core-service   │◄────────────│ scm-ai-service (Python)          │
                                   │ Spring Boot + JPA  │             │  LangChain agent → Claude/Bedrock│
                                   │ system of record   │             │  RAG: Titan embeddings + vectors │
                                   └──┬──────────▲──────┘             │  Computer vision: YOLO + OpenCV  │
                          domain events│          │alerts, inspections│  RL replenishment agents         │
                                       ▼          │ (Camel routes)    └──────┬──────────────▲────────────┘
                     ┌──────────────── Kafka ─────┴───────────────────────────┘ inspection  │ MCP tools
                     │ scm.inventory.events  scm.order.events  scm.shipment.events           │ (streamable HTTP)
                     │ scm.alerts            scm.vision.events                    ┌──────────┴──────────┐
                     └──────┬──────────────────────▲───────────────────────────── │ MCP server (FastMCP)│
                            ▼                      │ alerts                       │ 12 supply chain tools│
                     ┌──────────────────────────────┐                             └─────────────────────┘
                     │ scm-stream-processor (Flink) │
                     │ low stock · shipment delay · │
                     │ demand spike detection       │
                     └──────────────────────────────┘
```

| Technology | Where | What it does |
|---|---|---|
| **Spring Boot 3.5** | `scm-core-service` | REST API, JPA entities (suppliers, products, inventory, POs, shipments), business rules, SSE alert stream |
| **Apache Camel 4** | `scm-core-service/.../integration` | Supplier stock-feed ingestion (file → CSV → splitter → bean), Kafka consumers for Flink alerts, and a content-based router for vision inspections (damaged vs. clean), with a dead-letter channel |
| **Kafka** | all | Event backbone. All services share one JSON envelope: `eventId, type, source, entityId, timestamp, data` |
| **Apache Flink 1.20** | `scm-stream-processor` | Stateful stream processing: edge-triggered low-stock alerts (keyed state), shipment-ETA timers (processing-time timers), demand spikes (tumbling windows) |
| **LangChain 1.x** | `scm-ai-service/app/agent.py` | `create_agent` with Claude on Bedrock (`ChatBedrockConverse`), per-session memory (LangGraph checkpointer) |
| **MCP** | `scm-ai-service/app/mcp_server.py` | FastMCP server exposing 12 tools (inventory, POs, shipments, suppliers, alerts, policy search, RL replenishment). The agent loads them with `langchain-mcp-adapters`; Claude Desktop, Claude Code or an AgentCore Gateway can use them too |
| **Tools** | MCP server | Read tools, plus action tools (`create_purchase_order`, `update_shipment_status`) that go through the Spring Boot business rules |
| **RAG** | `scm-ai-service/app/rag.py` | Supplier contracts and SOPs in `knowledge_base/`, chunked and embedded with Titan v2 into LangChain's vector store (persisted to JSON); answers cite their source file |
| **AWS Bedrock AgentCore** | `app/agentcore_app.py`, `Dockerfile.agentcore` | The same agent, packaged for AgentCore Runtime (`BedrockAgentCoreApp`, `/invocations`), with session IDs mapped to agent memory threads |
| **Computer vision** | `scm-ai-service/app/vision.py` | Dock-photo inspection: YOLO object detection and counting (custom damage classes supported), QR code and barcode decoding with OpenCV, optional structured damage review by Claude. Results flow to Kafka, where Camel marks the shipment DAMAGED and raises an alert |
| **Reinforcement learning agents** | `scm-ai-service/app/rl/` | One policy-search agent (cross-entropy method) per SKU × warehouse learns its reorder point and order quantity in a Gymnasium simulator. Each is benchmarked against the SOP rule on held-out demand and used only where it was cheaper. Exposed in the UI, over REST, and as the MCP tool the copilot uses |
| **Angular 20** | `scm-ui` | Standalone components, signals, zoneless; live alerts over SSE |

## Running locally (no Docker)

Prerequisites: Java 21, Maven, Node 22, Python 3.11+, and AWS credentials with Bedrock access to Claude
and Titan Text Embeddings v2 (only needed for the assistant, RAG and Claude vision review).

The core service runs without Kafka by default: events are logged, and low-stock alerts are raised in-process.

```bash
# 1. Core service (http://localhost:8080, H2 console at /h2-console)
cd scm-core-service
mvn spring-boot:run

# 2. MCP server (http://localhost:8001/mcp)
cd scm-ai-service
python -m venv .venv && .venv\Scripts\activate        # source .venv/bin/activate on macOS/Linux
pip install -r requirements.txt
copy .env.example .env                                 # set AWS_REGION / BEDROCK_MODEL_ID
python -m app.mcp_server

# 3. AI service (http://localhost:8000/docs), in a second terminal
uvicorn app.main:app --port 8000

# 4. UI (http://localhost:4200, proxies /api and /ai)
cd scm-ui
npm install
npm start
```

To try the Camel feed route, copy `scm-core-service/samples/supplier-feed-sample.csv` into
`scm-core-service/data/inbox/supplier-feeds/`. The file is picked up, applied, and moved to `.done/`.

## Running the full stack (Docker)

```bash
cd scm-stream-processor && mvn package -DskipTests && cd ..   # builds the Flink job jar
docker compose up --build
```

| URL | Service |
|---|---|
| http://localhost:8081 | Angular UI |
| http://localhost:8080 | Core API |
| http://localhost:8000/docs | AI service (OpenAPI) |
| http://localhost:8082 | Flink dashboard |
| http://localhost:8090 | Kafka UI |

AWS credentials are passed from your environment or `~/.aws`.

## Bedrock model

The default is `anthropic.claude-opus-5`, set with `BEDROCK_MODEL_ID`. If your account requires a
cross-region inference profile, use its ID instead (e.g. `us.anthropic.claude-opus-5`). The model must be
enabled in the Bedrock console for your region.

## Deploying the agent to Bedrock AgentCore Runtime

```bash
pip install bedrock-agentcore-starter-toolkit
cd scm-ai-service
agentcore configure --entrypoint app/agentcore_app.py --name scm_agent
agentcore launch --env SCM_MCP_URL=https://<your-mcp-endpoint>/mcp --env BEDROCK_MODEL_ID=anthropic.claude-opus-5
agentcore invoke '{"prompt": "Which shipments are late and what penalties apply?"}'
```

The agent needs a reachable MCP endpoint. Either deploy `app/mcp_server.py` as a second AgentCore
Runtime with the MCP protocol, or register the core REST API as targets on an AgentCore Gateway.
`Dockerfile.agentcore` is a minimal ARM64 image if you prefer to build and push to ECR yourself.

## Computer vision models

`YOLO_MODEL` defaults to `yolo11n.pt` (COCO weights, downloaded on first use). COCO covers generic
object detection and counting but has no damage classes. For production damage detection, fine-tune a
YOLO model on your dock photos with classes such as `dent`, `tear`, `crushed` and `wet` (see `DAMAGE_LABELS`),
then point `YOLO_MODEL` at the weights. Until then, tick **Claude visual review** in the UI to get a damage
assessment from Claude.

## Reinforcement learning replenishment

**Problem.** A fixed rule ("order the standard quantity at the reorder point") ignores each item's cost
trade-offs. For a cheap carton, a stock-out costs far more than holding extra stock. For a $64 controller
board, carrying 100 units costs more than ordering smaller batches more often.

**Environment** (`app/rl/env.py`, a Gymnasium `Env`). One simulated day per step:
1. Orders placed `lead_time_days` earlier arrive.
2. The agent chooses how many units to order.
3. Poisson demand is served, and any unmet demand is lost.

The reward is the negative daily cost: holding (25%/yr of unit value), lost sales (1.5× unit price) and a
fixed cost per order.

**Parameters from live data.** Lead time comes from the supplier record, and the price and standard
quantity from the product. Daily demand is inferred by inverting the reorder-point formula in
`knowledge_base/reorder_policy.md`.

**Agent** (`app/rl/agent.py`). Policy-search RL with the cross-entropy method, one agent per SKU ×
warehouse.
- Each iteration samples 40 candidate (reorder point *s*, order quantity *Q*) policies.
- It runs them all on the same 100 simulated demand paths in a vectorized twin of the environment (a test
  checks the twin against the Gymnasium env).
- It refits to the cheapest 8.

The search starts from the SOP policy and trains in about one second per position. The result is a policy
a planner can read, e.g. "order 62 when position ≤ 42".

> Tabular Q-learning was tried first. Lost-sales costs land a full lead time (7–21 days) after the
> decision, the value estimates drowned in demand noise, and results varied wildly between random seeds.
> Policy search over interpretable policies was stable across seeds and beat the SOP rule on every
> seed-data item.

**Guardrail.** Each learned policy is evaluated against the SOP rule in the Gymnasium env on 50 held-out
demand scenarios that were never used in training. A recommendation uses the learned quantity only where
it had the lower cost. `policyUsed` says which one applied.

Benchmark on the seed-data item profiles (simulated 120-day cost, held-out demand, 3 training seeds each):

| Item | SOP s / Q | Learned s / Q | SOP cost | RL cost |
|---|---|---|---|---|
| Hex bolts @ WH-WEST | 120 / 400 | ≈103 / 336 | 436 | ≈396 (−9%) |
| Cartons @ WH-WEST | 1000 / 5000 | ≈857 / 3320 | 289 | ≈220 (−24%) |
| Stretch wrap @ WH-WEST | 30 / 100 | ≈33 / 110 | ≈220 | ≈207 (−6%) |
| Temp sensor @ WH-EAST | 50 / 300 | ≈48 / 225 | ≈202 | ≈160 (−21%) |
| Gateway board @ WH-EAST | 40 / 100 | ≈42 / 63 | ≈438 | ≈390 (−11%) |

| Endpoint / tool | Purpose |
|---|---|
| `GET /ai/rl/recommendations` | Order quantity per position from its live state (trains missing or stale agents first) |
| `POST /ai/rl/train {"iterations": 40}` | Retrain every agent |
| MCP `get_replenishment_recommendations` | Same data for the LangChain agent or any MCP client |
| UI **RL replenishment** page | Compare SOP and learned policies, cost and fill rate, and place the recommended PO in one click |

Trained policies are saved in `scm-ai-service/data/rl_policies.json`. An agent retrains automatically
when its position's reorder point, reorder quantity, price or lead time changes.

## Try it

- **Dashboard:** the seed data has two low-stock positions and one overdue ocean shipment (`TRK-DEMO000002`).
- **Inventory:** adjust `SKU-3002 @ WH-EAST` by `-90` and watch a LOW_STOCK alert appear live.
- **RL replenishment:** see where the learned policy beats the SOP rule, then place its PO.
- **Assistant:** ask *"Which items are below their reorder point, and what does the reorder policy say to do?"*
- **Vision:** open a shipment's **Inspect** link and upload a photo of the package.

## Tests

```bash
cd scm-core-service && mvn test          # service flows: low stock, PO receipt on delivery, local alerts
cd scm-ai-service && pytest              # RL env + agent, RAG index (offline), vision, MCP tools, event contract
cd scm-ui && npx ng build                # strict template type-check
```
