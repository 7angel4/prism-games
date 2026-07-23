#!/usr/bin/env python3
"""
Aggregate batch-run CSVs (produced by learning.RunPac) into mean +/- std tables
for the rebuttal, grouped by (model, propertyIndex, consts, explorer).

Usage:
    python3 aggregate.py results1.csv [results2.csv ...]

Outputs a per-group summary of episodes / samples / transitions / runtime /
values with mean, std and 95% CI (t-distribution) over seeds.
"""

import sys
import math
import csv
from collections import defaultdict

# t-distribution 97.5% quantiles for df = 1..30 (for 95% CIs)
T975 = [12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262, 2.228,
        2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093, 2.086,
        2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045, 2.042]

METRICS = ["episodes", "totalSamples", "totalTransitions", "runtimeMs",
           "deltaT", "robustValue", "robustValueGap", "pointValueGap"]


def mean_std_ci(xs):
    xs = [x for x in xs if not math.isnan(x)]
    n = len(xs)
    if n == 0:
        return (float("nan"), float("nan"), float("nan"), 0)
    m = sum(xs) / n
    if n == 1:
        return (m, 0.0, float("nan"), 1)
    var = sum((x - m) ** 2 for x in xs) / (n - 1)
    sd = math.sqrt(var)
    t = T975[min(n - 2, len(T975) - 1)]
    ci = t * sd / math.sqrt(n)
    return (m, sd, ci, n)


def main(paths):
    groups = defaultdict(list)
    for path in paths:
        with open(path) as f:
            for row in csv.DictReader(f):
                key = (row["model"], row["propertyIndex"], row["consts"], row["explorer"])
                groups[key].append(row)

    for key in sorted(groups):
        rows = groups[key]
        seeds = sorted(set(r["seed"] for r in rows))
        print("\n=== model=%s prop=%s consts=%s explorer=%s  (n=%d seeds: %s)"
              % (key + (len(seeds), ",".join(seeds))))
        for metric in METRICS:
            vals = []
            for r in rows:
                try:
                    vals.append(float(r[metric]))
                except (ValueError, KeyError):
                    vals.append(float("nan"))
            m, sd, ci, n = mean_std_ci(vals)
            if n == 0:
                continue
            print("  %-18s mean=%-14.6g std=%-12.6g 95%%CI=+/-%-12.6g" % (metric, m, sd, ci))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    main(sys.argv[1:])
