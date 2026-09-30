from scheduler_worker import task

# Same execution settings as the worker process. Runs once, before any test trains a model.
task.configure_torch(1)
