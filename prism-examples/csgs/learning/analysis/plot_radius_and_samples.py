#!/usr/bin/env python3
"""
Regenerate the "radius ratio" and "samples per episode" learning-dynamics
panels from a per-episode log produced by learning.RunPac. Companion to
plot_deltat_coverage.py (same log format, same PGF rendering setup); that
script covers the Delta_t/kappa_t(/true value gap) panel, this one covers
the other two panels used alongside it in fig:delayed-coord2-fine.

Usage:
    python3 plot_radius_and_samples.py LOG_CSV OUT_RADIUS_PDF OUT_SAMPLES_PDF
"""

import argparse
import csv

import matplotlib
# Same PGF backend as plot_deltat_coverage.py / plot_scaling.py, for
# consistent true-vector-LaTeX rendering across all regenerated figures.
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
    p.add_argument("out_radius_pdf")
    p.add_argument("out_samples_pdf")
    args = p.parse_args()

    rows = load(args.log_csv)
    t = [int(r["episode"]) for r in rows]
    max_radius = [float(r["maxRadius"]) for r in rows]
    avg_radius = [float(r["avgRadius"]) for r in rows]
    radius_ratio = [a / m if m > 0 else 0.0 for a, m in zip(avg_radius, max_radius)]
    num_samples = [int(r["numSamples"]) for r in rows]

    # --- radius ratio: alpha_t^avg / alpha_t^max, log-x, y in [0,1] ---
    fig, ax = plt.subplots(figsize=(3, 3))
    ax.plot(t, radius_ratio)
    ax.set_xscale("log")
    ax.set_xlabel(r"$t$ (log scale)")
    ax.set_ylabel(r"$\frac{\alpha_t^{\mathrm{avg}}}{\alpha_t^{\max}}$", rotation=0, labelpad=15)
    ax.set_ylim(0, 1)
    ax.grid(True)
    fig.tight_layout()
    fig.savefig(args.out_radius_pdf)
    print(f"wrote {args.out_radius_pdf}")

    # --- samples per episode: N^omega_t ---
    fig, ax = plt.subplots(figsize=(3, 3))
    ax.plot(t, num_samples, label=r"$N^\omega_t$")
    ax.set_xlabel(r"$t$")
    ax.set_ylabel(r"$N^\pi_t$", rotation=0, labelpad=10)
    ax.grid(True)
    fig.tight_layout()
    fig.savefig(args.out_samples_pdf)
    print(f"wrote {args.out_samples_pdf}")

    print(f"  final radius ratio: {radius_ratio[-1]:.4f}  (stabilised value: "
          f"{sum(radius_ratio[len(radius_ratio)//2:]) / len(radius_ratio[len(radius_ratio)//2:]):.4f} avg over 2nd half)")


if __name__ == "__main__":
    main()
