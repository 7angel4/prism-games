#!/usr/bin/env python3
r"""
Fit a log-log line (samples-to-convergence vs. a scaling parameter) from
learning.RunPac batch CSVs, e.g. to test the H^4 / (1/eps)^2 / |A| / |S|
dependence in the sample-complexity bound.

Usage:
    python3 fit_scaling.py CSV --const-key HB
    python3 fit_scaling.py CSV --column epsilon --invert
    python3 fit_scaling.py CSV --model-regex 'safe_risky_actions_m(\d+)' --transform 'lambda m: m**2'
    python3 fit_scaling.py CSV --const-key HB --exclude 1

One of --const-key, --column, --model-regex selects how the x-value for each
row is obtained:
  --const-key KEY      parse "KEY=<value>" out of the consts column (e.g. HB for H-sweep)
  --column NAME         use a column directly (e.g. epsilon)
  --model-regex REGEX   extract a numeric group from the model column (e.g. |A|/|S| sweeps)

Options:
  --transform EXPR   a Python lambda (as a string) applied to the extracted x-value
                      before fitting, e.g. 'lambda m: m**2' to convert an
                      actions-per-player m into |A|=m^2
  --invert            fit against 1/x instead of x (use for the eps-sweep, since
                      the bound is in 1/eps^2, not eps)
  --exclude V [V ...]  drop these x-values before fitting (e.g. --exclude 1 to
                       drop a known corner case, and compare against the full fit)
  --metric COL         which column to average and fit against (default totalSamples)

Prints, for each requested fit: the number of groups/seeds, per-group means,
the OLS slope (exponent), intercept, and R^2.
"""

import argparse
import csv
import math
import re
import sys
from collections import defaultdict


def load(path):
    with open(path) as f:
        return list(csv.DictReader(f))


def extract_x(row, args):
    if args.const_key:
        for part in row["consts"].split(";"):
            part = part.strip()
            if part.startswith(args.const_key + "="):
                return float(part.split("=", 1)[1])
        return None
    if args.column:
        v = row.get(args.column, "")
        return float(v) if v not in ("", "NaN") else None
    if args.model_regex:
        m = re.search(args.model_regex, row["model"])
        return float(m.group(1)) if m else None
    raise SystemExit("Specify one of --const-key, --column, --model-regex")


def ols_loglog(xs, ys):
    lx = [math.log(x) for x in xs]
    ly = [math.log(y) for y in ys]
    n = len(lx)
    mx, my = sum(lx) / n, sum(ly) / n
    num = sum((lx[i] - mx) * (ly[i] - my) for i in range(n))
    den = sum((lx[i] - mx) ** 2 for i in range(n))
    slope = num / den
    intercept = my - slope * mx
    pred = [slope * lx[i] + intercept for i in range(n)]
    ss_res = sum((ly[i] - pred[i]) ** 2 for i in range(n))
    ss_tot = sum((ly[i] - my) ** 2 for i in range(n))
    r2 = 1 - ss_res / ss_tot if ss_tot > 0 else float("nan")
    return slope, intercept, r2


def fit_and_report(groups, label):
    xs = sorted(groups)
    if len(xs) < 2:
        print(f"[{label}] need >=2 distinct x-values, got {len(xs)}; skipping")
        return
    means = []
    print(f"\n[{label}]")
    for x in xs:
        vals = groups[x]
        m = sum(vals) / len(vals)
        means.append(m)
        print(f"  x={x:<10g} n={len(vals):3d}  mean={m:.4g}")
    slope, intercept, r2 = ols_loglog(xs, means)
    print(f"  -> log-log OLS fit: exponent={slope:.3f}  R^2={r2:.4f}  (n_points={len(xs)})")
    return slope, r2


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("csv")
    p.add_argument("--const-key")
    p.add_argument("--column")
    p.add_argument("--model-regex")
    p.add_argument("--transform")
    p.add_argument("--invert", action="store_true")
    p.add_argument("--exclude", nargs="*", type=float, default=[])
    p.add_argument("--metric", default="totalSamples")
    args = p.parse_args()

    rows = load(args.csv)
    transform = eval(args.transform) if args.transform else (lambda x: x)

    groups = defaultdict(list)
    for row in rows:
        x = extract_x(row, args)
        if x is None:
            continue
        x = transform(x)
        if args.invert:
            x = 1.0 / x
        y_raw = row.get(args.metric, "")
        if y_raw in ("", "NaN"):
            continue
        groups[x].append(float(y_raw))

    if not groups:
        sys.exit("No matching rows found -- check --const-key/--column/--model-regex against the CSV.")

    fit_and_report(groups, "full fit")

    for ex in args.exclude:
        reduced = {x: v for x, v in groups.items() if abs(x - ex) > 1e-9 and (not args.invert or abs(1 / x - ex) > 1e-9)}
        fit_and_report(reduced, f"excluding x={ex}")


if __name__ == "__main__":
    main()
