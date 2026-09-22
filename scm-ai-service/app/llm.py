"""Claude on Amazon Bedrock via LangChain."""
from functools import lru_cache

from langchain_aws import BedrockEmbeddings, ChatBedrockConverse
from langchain_core.messages import BaseMessage

from app.config import get_settings


@lru_cache
def get_chat_model() -> ChatBedrockConverse:
    s = get_settings()
    # No temperature/top_p: current Claude models reject sampling parameters.
    return ChatBedrockConverse(
        model=s.bedrock_model_id,
        region_name=s.aws_region,
        max_tokens=16000,
    )


@lru_cache
def get_embeddings() -> BedrockEmbeddings:
    s = get_settings()
    return BedrockEmbeddings(model_id=s.bedrock_embedding_model_id, region_name=s.aws_region)


def message_text(message: BaseMessage) -> str:
    """Text of a model reply; Converse may return a list of blocks (text, reasoning, tool_use)."""
    content = message.content
    if isinstance(content, str):
        return content
    return "".join(
        block.get("text", "") for block in content if isinstance(block, dict) and block.get("type") == "text"
    )
