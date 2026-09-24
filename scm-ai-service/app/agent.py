"""LangChain agent: Claude on Bedrock + supply chain tools loaded from the MCP server."""
import asyncio
import logging
import uuid

from langchain.agents import create_agent
from langchain_core.messages import AIMessage, ToolMessage
from langchain_mcp_adapters.client import MultiServerMCPClient
from langgraph.checkpoint.memory import InMemorySaver

from app import observability
from app.config import get_settings
from app.llm import get_chat_model, message_text

log = logging.getLogger(__name__)

SYSTEM_PROMPT = """You are the operations copilot for a supply chain team. You can read live inventory,
purchase orders, shipments, suppliers and alerts, search supplier contracts and SOPs, and take actions
(raise purchase orders, update shipment status) through your tools.

How to work:
- Ground every number in a tool result; never estimate stock or dates.
- For reorder questions, use get_replenishment_recommendations (learned RL policies, benchmarked against the
  SOP rule) and check the reorder policy and supplier terms with search_policies. Say which policy backs
  each quantity and quote its simulated cost and fill rate.
- Creating a purchase order or changing a shipment status changes real records. Only do it when the user
  asked for that action; otherwise recommend it and let them confirm.
- Be concise: lead with the answer, then the supporting figures. Use tables for lists of items."""

_agent = None
_lock = asyncio.Lock()


async def get_agent():
    global _agent
    async with _lock:
        if _agent is None:
            client = MultiServerMCPClient(
                {"scm": {"transport": "streamable_http", "url": get_settings().scm_mcp_url}}
            )
            tools = await client.get_tools()
            log.info("Loaded %d MCP tools: %s", len(tools), [t.name for t in tools])
            _agent = create_agent(
                model=get_chat_model(),
                tools=tools,
                system_prompt=SYSTEM_PROMPT,
                checkpointer=InMemorySaver(),
            )
        return _agent


async def chat(session_id: str, message: str, runtime: str = "fastapi") -> dict:
    agent = await get_agent()
    # A known run ID lets the UI attach thumbs-up/down feedback to this turn's LangSmith trace.
    run_id = uuid.uuid4()
    result = await agent.ainvoke(
        {"messages": [{"role": "user", "content": message}]},
        config={"configurable": {"thread_id": session_id},
                **observability.run_config("supplychain-copilot", ["chat", runtime],
                                           {"session_id": session_id}, run_id=run_id)},
    )
    messages = result["messages"]
    # Messages produced in this turn are those after the last human message.
    last_human = max(i for i, m in enumerate(messages) if m.type == "human")
    turn = messages[last_human + 1:]
    tools_used = [m.name for m in turn if isinstance(m, ToolMessage)]
    reply = next((message_text(m) for m in reversed(turn) if isinstance(m, AIMessage)), "")
    return {"reply": reply, "tools_used": tools_used, "run_id": str(run_id), "traced": observability.enabled()}
