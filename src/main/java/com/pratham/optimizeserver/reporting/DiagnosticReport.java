package com.pratham.optimizeserver.reporting;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable representation of a generated diagnostic performance report and its saved file reference.
 */
public final class DiagnosticReport {

    private final String reportId;
    private final long timestampMillis;
    private final File savedFile;
    private final List<String> lines;

    public DiagnosticReport(String reportId, long timestampMillis, File savedFile, List<String> lines) {
        this.reportId = reportId;
        this.timestampMillis = timestampMillis;
        this.savedFile = savedFile;
        this.lines = Collections.unmodifiableList(new ArrayList<String>(lines));
    }

    public String getReportId() {
        return reportId;
    }

    public long getTimestampMillis() {
        return timestampMillis;
    }

    public File getSavedFile() {
        return savedFile;
    }

    public List<String> getLines() {
        return lines;
    }
}
