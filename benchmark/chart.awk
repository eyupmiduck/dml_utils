# chart.awk - emit an SVG line chart of the benchmark means.
#
# Reads the results CSV (threads,run,range_seconds,chunk_seconds,total_seconds)
# on stdin and writes an SVG to stdout. Pure awk: no plotting library needed, so
# the benchmark keeps working with nothing but Docker and the shell.

BEGIN { FS = "," }

NR == 1 { next }   # header

{
    t = $1 + 0
    n[t]++
    r[t] += $3 + 0
    c[t] += $4 + 0
    s[t] += $5 + 0
    if (!(t in seen)) { seen[t] = 1; order[++k] = t }
}

function xpos(i) {
    if (k == 1) return ml + pw / 2
    return ml + (i - 1) * pw / (k - 1)
}

function ypos(v) {
    return mt + ph - (v / ymax) * ph
}

END {
    if (k == 0) {
        print "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"760\" height=\"120\">" \
            "<text x=\"20\" y=\"60\" font-family=\"sans-serif\" font-size=\"14\">no results</text></svg>"
        exit
    }

    # Sort thread values ascending (k is small).
    for (i = 1; i <= k; i++)
        for (j = i + 1; j <= k; j++)
            if (order[i] > order[j]) { tmp = order[i]; order[i] = order[j]; order[j] = tmp }

    maxy = 0
    for (i = 1; i <= k; i++) {
        t = order[i]
        mr[i] = r[t] / n[t]
        mc[i] = c[t] / n[t]
        ms[i] = s[t] / n[t]
        if (mr[i] > maxy) maxy = mr[i]
        if (mc[i] > maxy) maxy = mc[i]
        if (ms[i] > maxy) maxy = ms[i]
    }
    if (maxy <= 0) maxy = 1
    ymax = maxy * 1.05

    W = 760; H = 420
    ml = 64; mright = 20; mt = 48; mb = 64
    pw = W - ml - mright
    ph = H - mt - mb

    printf "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\" font-family=\"sans-serif\">\n", W, H, W, H
    printf "  <rect width=\"%d\" height=\"%d\" fill=\"white\"/>\n", W, H
    printf "  <text x=\"%d\" y=\"26\" font-size=\"16\" font-weight=\"bold\" text-anchor=\"middle\">run_migration_chunks: mean time vs threads</text>\n", ml + pw / 2

    # Horizontal grid and y labels.
    for (g = 0; g <= 5; g++) {
        v = ymax * g / 5
        y = ypos(v)
        printf "  <line x1=\"%d\" y1=\"%.1f\" x2=\"%d\" y2=\"%.1f\" stroke=\"#e6e6e6\"/>\n", ml, y, ml + pw, y
        printf "  <text x=\"%d\" y=\"%.1f\" font-size=\"11\" text-anchor=\"end\" dominant-baseline=\"middle\">%.2f</text>\n", ml - 8, y, v
    }

    # Axes.
    printf "  <line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#333\"/>\n", ml, mt, ml, mt + ph
    printf "  <line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#333\"/>\n", ml, mt + ph, ml + pw, mt + ph

    # x ticks and labels.
    for (i = 1; i <= k; i++) {
        x = xpos(i)
        printf "  <line x1=\"%.1f\" y1=\"%d\" x2=\"%.1f\" y2=\"%d\" stroke=\"#333\"/>\n", x, mt + ph, x, mt + ph + 4
        printf "  <text x=\"%.1f\" y=\"%d\" font-size=\"10\" text-anchor=\"middle\">%d</text>\n", x, mt + ph + 18, order[i]
    }
    printf "  <text x=\"%d\" y=\"%d\" font-size=\"12\" text-anchor=\"middle\">threads</text>\n", ml + pw / 2, H - 12
    printf "  <text x=\"16\" y=\"%d\" font-size=\"12\" text-anchor=\"middle\" transform=\"rotate(-90 16 %d)\">seconds (mean)</text>\n", mt + ph / 2, mt + ph / 2

    # Series: total (green), chunk run (blue), range calc (red). Each polyline is
    # built inline because this awk cannot pass arrays to functions.
    pts = ""
    for (i = 1; i <= k; i++) pts = pts sprintf("%.1f,%.1f ", xpos(i), ypos(ms[i]))
    printf "  <polyline fill=\"none\" stroke=\"#2ca02c\" stroke-width=\"2\" points=\"%s\"/>\n", pts

    pts = ""
    for (i = 1; i <= k; i++) pts = pts sprintf("%.1f,%.1f ", xpos(i), ypos(mc[i]))
    printf "  <polyline fill=\"none\" stroke=\"#1f77b4\" stroke-width=\"2\" points=\"%s\"/>\n", pts

    pts = ""
    for (i = 1; i <= k; i++) pts = pts sprintf("%.1f,%.1f ", xpos(i), ypos(mr[i]))
    printf "  <polyline fill=\"none\" stroke=\"#d62728\" stroke-width=\"2\" points=\"%s\"/>\n", pts
    for (i = 1; i <= k; i++) {
        printf "  <circle cx=\"%.1f\" cy=\"%.1f\" r=\"3\" fill=\"#2ca02c\"/>\n", xpos(i), ypos(ms[i])
        printf "  <circle cx=\"%.1f\" cy=\"%.1f\" r=\"3\" fill=\"#1f77b4\"/>\n", xpos(i), ypos(mc[i])
        printf "  <circle cx=\"%.1f\" cy=\"%.1f\" r=\"3\" fill=\"#d62728\"/>\n", xpos(i), ypos(mr[i])
    }

    # Legend (top-left inside the plot).
    lx = ml + 12; ly = mt + 12
    printf "  <line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#2ca02c\" stroke-width=\"2\"/><text x=\"%d\" y=\"%d\" font-size=\"12\" dominant-baseline=\"middle\">total</text>\n", lx, ly, lx + 20, ly, lx + 26, ly
    printf "  <line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#1f77b4\" stroke-width=\"2\"/><text x=\"%d\" y=\"%d\" font-size=\"12\" dominant-baseline=\"middle\">chunk run</text>\n", lx, ly + 18, lx + 20, ly + 18, lx + 26, ly + 18
    printf "  <line x1=\"%d\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#d62728\" stroke-width=\"2\"/><text x=\"%d\" y=\"%d\" font-size=\"12\" dominant-baseline=\"middle\">range calc</text>\n", lx, ly + 36, lx + 20, ly + 36, lx + 26, ly + 36

    print "</svg>"
}
