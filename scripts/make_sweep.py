#!/usr/bin/env python3
"""Prints a POST /experiments body for a grid sweep over learning rate, width, depth, and optimizer.

The default grid has 5 x 5 x 2 x 2 = 100 configs, so --seeds 2 gives the 200-job batch. Example:

  python3 scripts/make_sweep.py --seeds 2 | curl -s -X POST localhost:8080/experiments \\
      -H 'Content-Type: application/json' --data @- | jq '{id, progress}'
"""

import argparse
import itertools
import json

LEARNING_RATES = [0.001, 0.003, 0.01, 0.03, 0.1]
HIDDEN_UNITS = [8, 16, 32, 64, 128]
HIDDEN_LAYERS = [1, 2]
OPTIMIZERS = ["adam", "sgd"]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--name", default="grid-sweep")
    parser.add_argument("--seeds", type=int, default=1, help="runs per config, with seeds 0..N-1")
    parser.add_argument("--epochs", type=int, default=20)
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--max-attempts", type=int, default=3)
    parser.add_argument("--limit", type=int, help="keep only the first N jobs")
    args = parser.parse_args()

    grid = itertools.product(LEARNING_RATES, HIDDEN_UNITS, HIDDEN_LAYERS, OPTIMIZERS, range(args.seeds))
    jobs = [
        {"seed": seed, "config": {"learningRate": lr, "hiddenUnits": units, "hiddenLayers": layers,
                                  "batchSize": args.batch_size, "epochs": args.epochs, "optimizer": optimizer,
                                  "weightDecay": 0.0}}
        for lr, units, layers, optimizer, seed in grid
    ][:args.limit]
    print(json.dumps({"name": args.name, "task": "synthetic-mlp-v1", "maxAttempts": args.max_attempts, "jobs": jobs}))


if __name__ == "__main__":
    main()
