# syntax=docker/dockerfile:1.26.0
FROM python:3.14.7-slim-trixie

# uv installs exactly what uv.lock pins, including the CPU-only torch build for Linux.
COPY --from=ghcr.io/astral-sh/uv:0.12.20 /uv /bin/uv

# One compute thread per worker process. The variables must be set before Python starts so that
# OpenMP/MKL see them; the worker also calls torch.set_num_threads (TORCH_NUM_THREADS, default 1).
ENV OMP_NUM_THREADS=1 \
    MKL_NUM_THREADS=1 \
    PYTHONUNBUFFERED=1 \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never

WORKDIR /app
COPY pyproject.toml uv.lock .python-version ./
RUN --mount=type=cache,target=/root/.cache/uv uv sync --frozen --no-dev
COPY scheduler_worker/ scheduler_worker/

RUN useradd --system --no-create-home --user-group worker
USER worker
ENTRYPOINT ["/app/.venv/bin/python", "-m", "scheduler_worker"]
