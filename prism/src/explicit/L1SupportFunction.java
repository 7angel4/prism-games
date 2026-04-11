package explicit;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.Comparator;

public final class L1SupportFunction {
    private L1SupportFunction() {}

    public static <Value> double solve(Distribution<Value> distr, double radius, double[] vect, MinMax minMax) {
        if (distr == null || distr.isEmpty()) {
            return 0.0;
        }
        if (distr.size() == 1) {
            Map.Entry<Integer, Value> e = distr.iterator().next();
            return vect[e.getKey()];
        }

        int n = distr.size();
        int[] idx = new int[n];
        double[] p = new double[n];
        double[] values = new double[n];

        int t = 0;
        Iterator<Map.Entry<Integer, Value>> it = distr.iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Value> e = it.next();
            idx[t] = e.getKey();
            p[t] = ((Number) e.getValue()).doubleValue();
            values[t] = vect[idx[t]];
            t++;
        }

        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingDouble(i -> values[i]));

        double budget = Math.max(0.0, radius) / 2.0;
        if (budget <= 1e-15) {
            double res = 0.0;
            for (int i = 0; i < n; i++) res += p[i] * values[i];
            return res;
        }

        boolean minimize = minMax.isMinUnc();
        int low = 0, high = n - 1;

        double delta;
        while (budget > 1e-15 && low < high) {
            if (minimize) {
                while (low < high && p[order[low]] >= 1.0 - 1e-15) low++;
                while (low < high && p[order[high]] <= 1e-15) high--;
                if (low >= high) break;

                int from = order[high];
                int to = order[low];
                delta = Math.min(budget, Math.min(p[from], 1.0 - p[to]));
                if (delta <= 1e-15) {
                    if (p[from] <= 1e-15) high--;
                    if (p[to] >= 1.0 - 1e-15) low++;
                    continue;
                }
                p[from] -= delta;
                p[to] += delta;
            } else {
                while (low < high && p[order[low]] <= 1e-15) low++;
                while (low < high && p[order[high]] >= 1.0 - 1e-15) high--;
                if (low >= high) break;

                int from = order[low];
                int to = order[high];
                delta = Math.min(budget, Math.min(p[from], 1.0 - p[to]));
                if (delta <= 1e-15) {
                    if (p[from] <= 1e-15) low++;
                    if (p[to] >= 1.0 - 1e-15) high--;
                    continue;
                }
                p[from] -= delta;
                p[to] += delta;
            }

            budget -= delta;
            if (p[order[low]] >= 1.0 - 1e-15) low++;
            if (p[order[high]] <= 1e-15) high--;
        }

        double res = 0.0;
        for (int i = 0; i < n; i++) res += p[i] * values[i];
        return res;
    }
}