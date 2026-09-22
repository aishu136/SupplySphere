"""Amazon Bedrock AgentCore Runtime entrypoint for the supply chain agent.

AgentCore Runtime hosts this container (port 8080, /invocations + /ping) with session isolation.
The agent reaches the MCP server at SCM_MCP_URL - host it as its own AgentCore Runtime (MCP protocol)
or put the tools behind an AgentCore Gateway.

Local test:
    python -m app.agentcore_app
    curl -X POST localhost:8080/invocations -H "Content-Type: application/json" \
         -d '{"prompt": "Which items are below their reorder point?"}'
"""
from bedrock_agentcore.runtime import BedrockAgentCoreApp

from app import agent

app = BedrockAgentCoreApp()


@app.entrypoint
async def invoke(payload: dict, context) -> dict:
    prompt = payload.get("prompt")
    if not prompt:
        return {"error": "payload must include 'prompt'"}
    session_id = getattr(context, "session_id", None) or payload.get("session_id", "default")
    return await agent.chat(session_id, prompt)


if __name__ == "__main__":
    app.run()
