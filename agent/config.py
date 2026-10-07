import os
from pathlib import Path

from dotenv import load_dotenv

# .env lives at the repo root, one level up from here
load_dotenv(Path(__file__).resolve().parent.parent / ".env")


class Config:
    gemini_key = os.getenv("GEMINI_API_KEY", "")
    # never hardcoded - which flash models are free is account specific and the
    # lineup moves. check your ai studio dashboard.
    model = os.getenv("GEMINI_MODEL", "gemini-3.6-flash")
    judge_model = os.getenv("GEMINI_JUDGE_MODEL", "gemini-3.1-flash-lite")
    # tried when the main model keeps answering "busy". a different model has its own
    # capacity, so it is often fine while the main one is overloaded. empty = none
    fallback_model = os.getenv("GEMINI_FALLBACK_MODEL", "gemini-flash-lite-latest")
    rpm_limit = int(os.getenv("GEMINI_RPM_LIMIT", "14"))
    embed_model = os.getenv("GEMINI_EMBED_MODEL", "gemini-embedding-001")

    # the same fixed "today" the platform uses, so dates the agent writes and
    # dates the platform checks agree
    sim_date = os.getenv("SIM_DATE", "2026-03-10")

    # the agent's own knowledge index (pgvector). not the system of record - that
    # is the platform's mysql, which the agent still has no credentials for
    vector_db_url = os.getenv("VECTOR_DB_URL", "postgresql+psycopg://rag:rag@localhost:5433/rag")

    platform_url = os.getenv("PLATFORM_BASE_URL", "http://localhost:8080")
    redis_host = os.getenv("REDIS_HOST", "localhost")
    redis_port = int(os.getenv("REDIS_PORT", "6379"))

    # graph limits. an unbounded agent loop is a bill and an incident report.
    max_steps = int(os.getenv("AGENT_MAX_STEPS", "25"))
    max_gather_turns = int(os.getenv("AGENT_MAX_GATHER_TURNS", "4"))
    wall_clock_seconds = int(os.getenv("AGENT_WALL_CLOCK_SECONDS", "120"))

    @classmethod
    def require_key(cls):
        if not cls.gemini_key:
            raise RuntimeError(
                "GEMINI_API_KEY is not set. Get a free one at "
                "https://aistudio.google.com/apikey and put it in .env"
            )


cfg = Config()
