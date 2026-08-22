#!/usr/bin/env python3
"""
Coverage-progress panel: kappa_t (known-slot fraction) and the average-to-max
confidence-radius ratio, from a per-episode log produced by learning.RunPac.
Companion to plot_deltat_valuegap.py (Delta_t / true value gap) and the
numSamples panel in plot_radius_and_samples.py; together these form the
three panels of fig:delayed-coord1-fine.

Both quantities are naturally in [0,1], so they share one linear axis
(consistent with the numSamples panel's linear x-scale, unlike the older
log-x radius-ratio-only plot).

Usage:
    python3 plot_kappa_radius.py LOG_CSV OUT_PDF

Exact command used to regenerate the paper's copy (run from
prism-examples/csgs/learning/):
    python3 analysis/plot_kappa_radius.py \
      logs/e0vg/delayed_coord1_rmdp_s1.csv \
      analysis/plots/delayed_coord1/delayed_coord1_kappa_radius.pdf
    cp analysis/plots/delayed_coord1/delayed_coord1_kappa_radius.pdf \
      /tmp/paper-updated-new/experiments/delayed_coord1/delayed_coord1_kappa_radius.pdf
"""

import argparse
import csv

import matplotlib
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
    "pgf.preamble": r"\usepackage[T1]{fontenc}",
})


def load(path):
    with open(path) as f:
        return list(csv.DictReader(f))


def main():
    p = argparse.ArgumentParser()
    p.add_argument("log_csv")
    p.add_argument("out_pdf")
    args = p.parse_args()

    rows = load(args.log_csv)
    t = [int(r["episode"]) for r in rows]
    kappa_t = [1.0 - float(r["propUnknown"]) for r in rows]
    max_radius = [float(r["maxRadius"]) for r in rows]
    avg_radius = [float(r["avgRadius"]) for r in rows]
    radius_ratio = [a / m if m > 0 else 0.0 for a, m in zip(avg_radius, max_radius)]

    c_kappa, c_ratio = "tab:orange", "tab:purple"

    fig, ax = plt.subplots(figsize=(4.6, 3.6))
    ax.step(t, kappa_t, color=c_kappa, linewidth=2, linestyle="--", where="post",
            label=r"$\kappa_t$")
    ax.plot(t, radius_ratio, color=c_ratio, linewidth=1.5,
            label=r"$\alpha_t^{\mathrm{avg}}/\alpha_t^{\max}$")
    ax.set_xlabel(r"$t$")
    ax.set_ylabel(r"Coverage / radius ratio")
    ax.set_ylim(0, 1)
    ax.grid(True, alpha=0.6)
    ax.legend(loc="lower right", framealpha=0.9)

    fig.tight_layout()
    fig.savefig(args.out_pdf)
    print(f"wrote {args.out_pdf}")


if __name__ == "__main__":
    main()
