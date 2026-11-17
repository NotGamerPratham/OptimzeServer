package com.pratham.optimizeserver.reporting;

import java.util.List;

/**
 * Renders metric series as compact Unicode block sparklines (for chat and console).
 */
public final class Sparkline {

    private static final char[] BLOCKS = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};

    private Sparkline() {
    }

    /**
     * Averages {@code values} into at most {@code columns} buckets (oldest to newest).
     */
    public static double[] bucket(List<Double> values, int columns) {
        int n = values != null ? values.size() : 0;
        if (n == 0 || columns <= 0) {
            return new double[0];
        }
        int width = Math.min(columns, n);
        double[] out = new double[width];
        for (int b = 0; b < width; b++) {
            int from = (int) ((long) b * n / width);
            int to = (int) ((long) (b + 1) * n / width);
            double sum = 0.0;
            for (int i = from; i < to; i++) {
                sum += values.get(i);
            }
            out[b] = sum / Math.max(1, to - from);
        }
        return out;
    }

    /**
     * Draws the values between {@code floor} and {@code ceiling}; values outside are clamped.
     */
    public static String render(double[] values, double floor, double ceiling) {
        StringBuilder sb = new StringBuilder(values.length);
        double span = ceiling - floor;
        for (double v : values) {
            double ratio = span <= 0.0 ? 0.0 : (v - floor) / span;
            ratio = Math.max(0.0, Math.min(1.0, ratio));
            sb.append(BLOCKS[(int) Math.round(ratio * (BLOCKS.length - 1))]);
        }
        return sb.toString();
    }
}
