#!/usr/bin/env python3
"""
Regenerate the "Delta_t / kappa_t (/ true value gap)" learning-dynamics figure
(paper Fig. 1(a) style), from a per-episode log produced by learning.RunPac
(prism-examples/csgs/learning/logs/<subdir>/<model><prop>_<explorer>_s<seed>.csv).

Usage:
    python3 plot_deltat_coverage.py LOG_CSV OUT_PDF [--title TITLE]

Plots:
  - Delta_t (left axis, log scale, solid blue)
  - kappa_t = 1 - propUnknown, i.e. fraction of known (s,a) slots (right axis,
    linear 0-1, dashed orange)
  - trueValueGap, if any non-NaN rows exist (plotted on the LEFT axis as a
    green dash-dot line; exact-zero points are dropped since log(0) is
    undefined, and are instead marked with a single annotation at the first
    episode the gap hits exactly 0 -- consistent with how we handled this
    for Safe-vs-Risky in the rebuttal). If the log has no valueGapEvery data
    (trueValueGap is NaN throughout), this line is simply omitted.
"""

import argparse
import csv

import matplotlib
# PGF backend = true vector LaTeX text (pdflatex), avoiding the dvipng
# rasterisation step that was silently dropping minus signs on this machine
# under the default text.usetex path (see plot_scaling.py for how this was
# confirmed). Also matches the reference style (mixed_ne1_g(r).pdf) exactly.
matplotlib.use("pgf")
import matplotlib.pyplot as plt

plt.rcParams.update({
    "font.family": "serif",
    "font.size": 12,
    "axes.labelsize": 12,
    "axes.titlesize": 12,
    "legend.fontsize": 10,
    "xtick.labelsize": 10,
    "ytick.labelsize": 10,
    "lines.linewidth": 2,
    "text.usetex": True,
    "pgf.texsystem": "pdflatex",
    "pgf.rcfonts": False,
    # T1 fontenc so plain '<'/'>' render as literal characters, not OT1 ligatures
    "pgf.preamble": r"\usepackage[T1]{fontenc}",
})


def load(path):
    with open(path) as f:
        return list(csv.DictReader(f))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("log_csv")
    p.add_argument("out_pdf")
    p.add_argument("--title", default=None)
    args = p.parse_args()

    rows = load(args.log_csv)
    t = [int(r["episode"]) for r in rows]
    delta_t = [float(r["deltaT"]) for r in rows]
    kappa_t = [1.0 - float(r["propUnknown"]) for r in rows]

    gap_pts = [(int(r["episode"]), float(r["trueValueGap"])) for r in rows if r["trueValueGap"] not in ("", "NaN")]

    fig, ax1 = plt.subplots(figsize=(4.6, 3.6))
    c_delta, c_kappa, c_gap = "tab:blue", "tab:orange", "tab:green"

    ax1.plot(t, delta_t, color=c_delta, linewidth=2, label=r"$\Delta_t$")
    ax1.set_yscale("log")
    ax1.set_xlabel(r"$t$")
    ax1.set_ylabel(r"$\Delta_t$ (log scale)", color=c_delta)
    ax1.tick_params(axis="y", labelcolor=c_delta)
    ax1.grid(True, alpha=0.6)

    ax2 = ax1.twinx()
    ax2.step(t, kappa_t, color=c_kappa, linewidth=2, linestyle="--", where="post", label=r"$\kappa_t$")
    ax2.set_ylabel(r"$\kappa_t$", color=c_kappa)
    ax2.tick_params(axis="y", labelcolor=c_kappa)
    ax2.set_ylim(0, 1)

    lines1, labels1 = ax1.get_legend_handles_labels()

    if gap_pts:
        # positive (>0) points plotted on the log axis directly; exact-0 points
        # can't be shown on a log scale, so we annotate the first zero-crossing.
        pos = [(x, y) for x, y in gap_pts if y > 0]
        zero = [(x, y) for x, y in gap_pts if y == 0]
        if pos:
            gx, gy = zip(*pos)
            ax1.plot(gx, gy, color=c_gap, linewidth=1.5, linestyle="-.",
                     label=r"$V^\star-u(\hat\sigma_t,P^\star)$")
        if zero:
            zx0 = min(x for x, _ in zero)
            ax1.axvline(zx0, color=c_gap, linewidth=0.8, alpha=0.5)
            ax1.annotate(f"gap=0 from t={zx0}", xy=(zx0, ax1.get_ylim()[0]),
                         xytext=(5, 5), textcoords="offset points",
                         fontsize=7, color=c_gap)
        lines1, labels1 = ax1.get_legend_handles_labels()

    lines2, labels2 = ax2.get_legend_handles_labels()
    ax1.legend(lines1 + lines2, labels1 + labels2, loc="center right", framealpha=0.9)

    if args.title:
        ax1.set_title(args.title)
    fig.tight_layout()
    fig.savefig(args.out_pdf)
    print(f"wrote {args.out_pdf}")
    if not gap_pts:
        print("NOTE: no trueValueGap data in this log (run wasn't launched with -valueGapEvery) -- plotted Delta_t/kappa_t only.")


if __name__ == "__main__":
    main()
