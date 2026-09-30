"""Entry point: python -m scheduler_worker (configured through environment variables)."""

import logging
import signal
import sys

import torch

from . import logs, task
from .client import ApiClient
from .config import WorkerSettings
from .worker import Worker


def main() -> int:
    settings = WorkerSettings.from_env()
    logs.configure(settings.log_format, {"service.name": "scheduler-worker", "workerId": settings.worker_id})
    task.configure_torch(settings.torch_threads)
    logging.getLogger(__name__).info("torch %s with %d compute thread(s)", torch.__version__, torch.get_num_threads(),
                                     extra={"event.action": "worker.configured", "torchVersion": torch.__version__,
                                            "torchThreads": torch.get_num_threads()})
    client = ApiClient(settings.api_url, connect_timeout=settings.connect_timeout_seconds,
                       read_timeout=settings.read_timeout_seconds, report_attempts=settings.report_attempts)
    worker = Worker(settings, client)
    # Python installs no SIGTERM handler by default. As PID 1 in a container, the process would
    # then ignore docker stop's SIGTERM until SIGKILL arrives.
    signal.signal(signal.SIGTERM, worker.handle_signal)
    signal.signal(signal.SIGINT, worker.handle_signal)
    worker.run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
