#!/usr/bin/env python3
r"""
Log-log scaling plot with error bars (mean +- std over seeds) and a fitted
power-law line, from learning.RunPac batch CSVs. Companion to fit_scaling.py
(same x-extraction options); use that first to see the numbers, this to plot
them.

Usage:
    python3 plot_scaling.py CSV OUT_PDF --xlabel '$|S|$' [--const-key HB]
        [--column epsilon] [--model-regex REGEX] [--transform EXPR]
        [--invert] [--exclude V ...] [--metric COL] [--title T] [--color C]
        [--symbol '|S|']

Rows are de-duplicated by (x-value, seed) before averaging, so an
accidentally-repeated run doesn't double-count. Instead of a legend, the
fitted power law is shown as an in-axes equation, e.g. "$N \propto |S|^{1.47}
\;(R^2=0.998)$", placed in whichever corner the data doesn't occupy.
"""

import argparse
import csv
import math
import re
from collections import defaultdict
from matplotlib.ticker import FuncFormatter, LogLocator

import matplotlib
# The PGF backend emits true vector LaTeX text (via pdflatex) instead of
# matplotlib's default text.usetex path, which rasterises each text element
# through dvipng -- on this machine that rasterisation step was silently
# dropping minus signs (confirmed at the PDF level: "$-1$" rendered as "1").
# PGF avoids that entirely and is what produced the reference style
# (mixed_ne1_g(r).pdf)'s exact look.
matplotlib.use("pgf")
import matplotlib.pyplot as plt

plt.rcParams.update({
    "font.family": "serif",
    "font.size": 12,
    "axes.labelsize": 14,
    "axes.titlesize": 14,
    "legend.fontsize": 14,
    "xtick.labelsize": 12,
    "ytick.labelsize": 12,
    "lines.linewidth": 2,
    "text.usetex": True,
    "pgf.texsystem": "pdflatex",
    "pgf.rcfonts": False,  # respect font.family above instead of pgf's own font detection
    # matplotlib's pgf backend doesn't load T1 fontenc by default, so plain
    # (non-math) '<' and '>' render as OT1 ligatures ("<" -> an inverted-!
    # looking glyph) instead of the literal characters. T1 fixes this generally.
    "pgf.preamble": r"\usepackage[T1]{fontenc}",
})


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


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("csv")
    p.add_argument("out_pdf")
    p.add_argument("--const-key")
    p.add_argument("--column")
    p.add_argument("--model-regex")
    p.add_argument("--transform")
    p.add_argument("--invert", action="store_true")
    p.add_argument("--exclude", nargs="*", type=float, default=[])
    p.add_argument("--metric", default="totalSamples")
    p.add_argument("--xlabel", default="parameter")
    p.add_argument("--ylabel", default="samples (log scale)")
    p.add_argument("--symbol", default=None, help="bare variable name for the fit equation, e.g. '|S|'; defaults to --xlabel with $ stripped")
    p.add_argument("--title", default=None)
    p.add_argument("--color", default="tab:blue")
    p.add_argument("--extra-point", action="append", default=[],
                    metavar="X:MEAN:STD:N",
                    help="manually inject a point sourced from a different CSV/config "
                         "(e.g. a base-case run that predates this sweep's file naming); "
                         "repeatable. X is in natural (display) units, e.g. |S|=4.")
    p.add_argument(
        "--x-tick-power",
        type=int,
        default=None,
        help="factor 10^power out of x-axis tick labels; "
            "e.g. -1 displays 0.1, 0.2, ... as 1, 2, ... and "
            "adds ×10^power to the x-axis label"
    )
    args = p.parse_args()

    rows = load(args.csv)
    transform = eval(args.transform) if args.transform else (lambda x: x)

    seen = set()
    groups = defaultdict(list)
    for row in rows:
        x = extract_x(row, args)
        if x is None:
            continue
        x_disp = transform(x)
        y_raw = row.get(args.metric, "")
        if y_raw in ("", "NaN"):
            continue
        key = (x_disp, row.get("seed"))
        if key in seen:
            continue
        seen.add(key)
        x_fit = (1.0 / x_disp) if args.invert else x_disp
        groups[x_fit].append((x_disp, float(y_raw)))

    for ex in list(args.exclude):
        groups = {xf: v for xf, v in groups.items() if abs((1 / xf if args.invert else xf) - ex) > 1e-9}

    xs_fit = sorted(groups)
    xs_disp = [groups[xf][0][0] for xf in xs_fit]
    means = [sum(y for _, y in groups[xf]) / len(groups[xf]) for xf in xs_fit]
    ns = [len(groups[xf]) for xf in xs_fit]
    stds = []
    for xf in xs_fit:
        vals = [y for _, y in groups[xf]]
        n = len(vals)
        m = sum(vals) / n
        stds.append((sum((v - m) ** 2 for v in vals) / (n - 1)) ** 0.5 if n > 1 else 0.0)

    for spec in args.extra_point:
        x_disp_e, mean_e, std_e, n_e = spec.split(":")
        x_disp_e, mean_e, std_e, n_e = float(x_disp_e), float(mean_e), float(std_e), int(n_e)
        x_fit_e = (1.0 / x_disp_e) if args.invert else x_disp_e
        pos = next((i for i, xf in enumerate(xs_fit) if xf > x_fit_e), len(xs_fit))
        xs_fit.insert(pos, x_fit_e)
        xs_disp.insert(pos, x_disp_e)
        means.insert(pos, mean_e)
        stds.insert(pos, std_e)
        ns.insert(pos, n_e)

    slope, intercept, r2 = ols_loglog(xs_fit, means)

    fig, ax = plt.subplots(figsize=(4.2, 3.4))
    ax.errorbar(xs_disp, means, yerr=stds, fmt="o", color=args.color, capsize=3,
                markersize=5, linewidth=0, elinewidth=1.2)

    # fitted power-law line, in *fit* x-units (1/x if inverted) but drawn at
    # the displayed x positions
    lo, hi = min(xs_fit), max(xs_fit)
    line_fit = [lo * (hi / lo) ** (i / 50) for i in range(51)]
    line_y = [math.exp(intercept) * xf ** slope for xf in line_fit]
    line_disp = [(1.0 / xf) if args.invert else xf for xf in line_fit]
    order = sorted(range(len(line_disp)), key=lambda i: line_disp[i])
    ax.plot([line_disp[i] for i in order], [line_y[i] for i in order],
            color=args.color, linestyle="--", linewidth=1.2, alpha=0.7)

    ax.set_xscale("log")
    ax.set_yscale("log")

    # The x-axis is always log-scaled. By default, retain Matplotlib's normal
    # log tick formatting. With --x-tick-power, instead show mantissas after
    # factoring out the specified power of ten.
    #
    # For example, with --x-tick-power -1:
    #     0.1, 0.2, 0.5, 1, 2, 5, 10
    # are displayed as:
    #     1,   2,   5,   10, 20, 50, 100
    # with ×10^{-1} stated in the axis label.
    if args.x_tick_power is not None:
        scale = 10 ** args.x_tick_power

        # Show every integer 1--9 within each decade, rather than only at
        # powers of ten.
        ax.xaxis.set_major_locator(
            LogLocator(base=10, subs=(1.0,))
        )
        ax.xaxis.set_minor_locator(
            LogLocator(base=10, subs=tuple(range(2, 10)))
        )

        tick_formatter = FuncFormatter(
            lambda x, pos: f"{x / scale:g}"
        )
        ax.xaxis.set_major_formatter(tick_formatter)
        ax.xaxis.set_minor_formatter(tick_formatter)

        ax.set_xlabel(
            rf"{args.xlabel} ($\times 10^{{{args.x_tick_power}}}$; log scale)"
        )
    else:
        ax.set_xlabel(args.xlabel + " (log scale)")

    ax.set_ylabel(args.ylabel)
    ax.grid(True, which="both", alpha=0.3)

    symbol = args.symbol or args.xlabel.replace("$", "")
    eqn = rf"$N^\pi \propto {symbol}^{{{slope:.2f}}}$"
    # display-space slope sign: inverting the x-axis for plotting flips it
    disp_slope = -slope if args.invert else slope
    max_rel_std = max((s / m for s, m in zip(stds, means) if m > 0), default=0.0)
    # mathtext (no real LaTeX backend, see rcParams above) doesn't understand
    # size commands like \footnotesize or the \% escape, so the smaller
    # second line is its own ax.text() call with an explicit fontsize, and
    # "%" is kept outside the $...$ math span as plain text.
    ha = "left" if disp_slope > 0 else "right"
    x0 = 0.05 if disp_slope > 0 else 0.95
    ax.text(x0, 0.95, eqn, transform=ax.transAxes, ha=ha, va="top", fontsize=14)
    if max_rel_std * 100 < 1.0:
        # error bars (+-1 std over seeds) exist but are smaller than the marker
        # at this scale -- say so explicitly rather than let them look absent
        note = rf"($R^2={r2:.3f}$; error < {max_rel_std*100:.2f}%)"
        ax.text(x0, 0.85, note, transform=ax.transAxes, ha=ha, va="top", fontsize=12)

    if args.title:
        ax.set_title(args.title)
    fig.tight_layout()
    fig.savefig(args.out_pdf)
    print(f"wrote {args.out_pdf}")
    for xd, m, s, n in zip(xs_disp, means, stds, ns):
        print(f"  x={xd:<10g} mean={m:.4g} std={s:.4g} n={n}")
    print(f"  exponent={slope:.4f}  R^2={r2:.4f}")


if __name__ == "__main__":
    main()
