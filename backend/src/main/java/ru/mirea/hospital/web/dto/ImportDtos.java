package ru.mirea.hospital.web.dto;

import java.util.List;

/** DTO предварительной проверки и применения CSV. */
public final class ImportDtos {
    private ImportDtos() {
    }

    public enum ImportMode { CREATE_ONLY, UPSERT_BY_EXTERNAL_ID }

    public record RowIssue(long row, String field, String message) {
    }

    public record ImportPreview(int totalRows, int validRows, int invalidRows, List<RowIssue> issues) {
    }

    public record ImportResult(int totalRows, int created, int updated, int skipped, List<RowIssue> issues) {
    }
}
