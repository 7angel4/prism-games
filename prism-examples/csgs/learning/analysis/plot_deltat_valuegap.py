#!/usr/bin/env python3
"""
Delta_t / true value gap learning-dynamics panel, from a per-episode log
produced by learning.RunPac. Companion to plot_kappa_radius.py, which covers
the other two "coverage progress" quantities (kappa_t, radius ratio) that
used to share this panel.

Usage:
    python3 plot_deltat_valuegap.py LOG_CSV OUT_PDF [--final-point EPISODE:GAP]

Exact command used to regenerate the paper's copy (run from
prism-examples/csgs/learning/):
    python3 analysis/plot_deltat_valuegap.py \
      logs/e0vg/delayed_coord1_rmdp_s1.csv \
      analysis/plots/delayed_coord1/delayed_coord1_DeltaT_valuegap.pdf \
      --final-point "1409831:0.0"
    cp analysis/plots/delayed_coord1/delayed_coord1_DeltaT_valuegap.pdf \
      /tmp/paper-updated-new/experiments/delayed_coord1/delayed_coord1_DeltaT_valuegap.pdf
"1409831:0.0" is episode:final_gap for this specific run (seed 1, eps=0.2,
property 1), read from results/batch/e0_valuegap_dc.csv -- not derived by
the script, so update it if regenerating against a different run.

Plots Delta_t (solid blue) and the true value gap (green dash-dot). Default
mode is 'shared-log': both series share a single log-scale axis, so their
magnitudes are directly comparable -- including where one crosses the other,
which turns out to be a real and meaningful event for Delayed Coordination
(Delta_t drops below the still-elevated gap right around the run's main
staircase drop, and stays below it until the gap itself finally resolves at
termination; see the discussion this motivated in app:RQ-learning-dynamics).
Exact zero can't be shown on a log axis, so it's represented via
--final-point's star at the axis floor -- Delta_t's own wide range already
gives this enough room to read clearly, no manual tuning needed.

Two alternatives, --gap-scale linear or log, instead give the gap its own
twin axis (linear is the cleaner choice there if a future run's gap stays
within a narrow range and you want exact zero represented natively rather
than via a floor; log if it genuinely spans multiple orders of magnitude
but a shared axis with Delta_t isn't wanted).
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
    p.add_argument("--title", default=None)
    p.add_argument("--final-point", metavar="EPISODE:GAP", default=None,
                    help="mark the actual reported final value gap (computed once, "
                         "post-loop, after the run stops), connected to the last "
                         "plotted periodic checkpoint -- this is what every other "
                         "table/figure in the paper reports, and can differ from "
                         "the last periodic in-loop checkpoint when the run is "
                         "still resolving right at the stopping boundary.")
    p.add_argument("--gap-scale", choices=["linear", "log", "shared-log"], default="shared-log",
                    help="scale for the value-gap series (default: shared-log, "
                         "plotted directly on Delta_t's left log axis, no twin "
                         "axis, so both are on the same numeric scale and "
                         "directly comparable, e.g. to see where one "
                         "crosses the other.")
    args = p.parse_args()

    rows = load(args.log_csv)
    t = [int(r["episode"]) for r in rows]
    delta_t = [float(r["deltaT"]) for r in rows]
    gap_pts = [(int(r["episode"]), float(r["trueValueGap"])) for r in rows if r["trueValueGap"] not in ("", "NaN")]

    fig, ax1 = plt.subplots(figsize=(4.6, 3.6))
    c_delta, c_gap = "tab:blue", "tab:green"

    ax1.plot(t, delta_t, color=c_delta, linewidth=2, label=r"$\Delta_t$")
    ax1.set_yscale("log")
    ax1.set_xlabel(r"$t$")
    ax1.grid(True, alpha=0.6)

    mode = args.gap_scale
    shared = mode == "shared-log"
    linear = mode == "linear"

    if shared:
        # Both series on ax1 directly (no twin axis): same numeric scale,
        # so crossings between Delta_t and the gap are directly readable.
        ax1.set_ylabel(r"Error / value gap (log scale)")
        ax2 = ax1
    else:
        ax1.set_ylabel(r"$\Delta_t$ (log scale)", color=c_delta)
        ax1.tick_params(axis="y", labelcolor=c_delta)
        ax2 = ax1.twinx()
        if linear:
            gmax = max((y for _, y in gap_pts), default=1.0)
            ax2.set_ylim(0, gmax * 1.08)
            ax2.set_ylabel(r"$V^\star-u(\hat\sigma_t,P^\star)$", color=c_gap)
        else:
            ax2.set_yscale("log")
            ax2.set_ylim(0.03, 3)
            ax2.set_ylabel(r"$V^\star-u(\hat\sigma_t,P^\star)$ (log scale)", color=c_gap)
        ax2.tick_params(axis="y", labelcolor=c_gap)

    # In shared mode ax2 is ax1, so we must only read its legend handles
    # once, after everything is plotted -- otherwise Delta_t's entry (added
    # before this point) gets picked up again alongside the gap's.
    lines1, labels1 = ([], []) if shared else ax1.get_legend_handles_labels()
    lines2, labels2 = [], []

    pos = []
    if gap_pts:
        if linear:
            # Exact 0 is representable directly on a linear axis, so there's
            # no need to drop/annotate zero points as a special case.
            pos = gap_pts
            gx, gy = zip(*pos)
            ax2.plot(gx, gy, color=c_gap, linewidth=1.5, linestyle="-.",
                     label=r"$V^\star-u(\hat\sigma_t,P^\star)$")
        else:
            pos = [(x, y) for x, y in gap_pts if y > 0]
            zero = [(x, y) for x, y in gap_pts if y == 0]
            if pos:
                gx, gy = zip(*pos)
                ax2.plot(gx, gy, color=c_gap, linewidth=1.5, linestyle="-.",
                         label=r"$V^\star-u(\hat\sigma_t,P^\star)$")
            if zero:
                zx0 = min(x for x, _ in zero)
                ax1.axvline(zx0, color=c_gap, linewidth=0.8, alpha=0.5)
                ax1.annotate(f"gap=0 from t={zx0}", xy=(zx0, ax1.get_ylim()[0]),
                             xytext=(5, 5), textcoords="offset points",
                             fontsize=7, color=c_gap)
        lines2, labels2 = ax2.get_legend_handles_labels()

    if args.final_point:
        fep, fgap = args.final_point.split(":")
        fep, fgap = int(fep), float(fgap)
        if linear:
            fy = fgap
        else:
            ymin = ax2.get_ylim()[0] if pos else ax1.get_ylim()[0]
            fy = fgap if fgap > 0 else ymin
        if pos:
            lx, ly = pos[-1]
            ax2.plot([lx, fep], [ly, fy], color=c_gap, linewidth=1.5,
                     linestyle="-.", zorder=4)
        label = r"final $u(\hat\sigma,P^\star)$" if fgap > 0 else r"final: gap $=0$"
        ax2.plot([fep], [fy], marker="*", markersize=13, color=c_gap,
                 linestyle="none", zorder=5, clip_on=(fgap > 0 or linear), label=label)
        lines2, labels2 = ax2.get_legend_handles_labels()

    ax1.legend(lines1 + lines2, labels1 + labels2, loc="upper right",
               bbox_to_anchor=(0.99, 0.99), framealpha=0.9, fontsize=9)

    if args.title:
        ax1.set_title(args.title)
    fig.tight_layout()
    fig.savefig(args.out_pdf)
    print(f"wrote {args.out_pdf}")
    if not gap_pts:
        print("NOTE: no trueValueGap data in this log -- plotted Delta_t only.")


if __name__ == "__main__":
    main()
