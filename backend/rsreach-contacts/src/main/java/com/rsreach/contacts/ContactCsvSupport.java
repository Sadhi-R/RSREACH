package com.rsreach.contacts;

import com.rsreach.common.error.ApiException;
import com.rsreach.common.error.ErrorCode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class ContactCsvSupport {

    private static final Pattern FORMULA_RISK = Pattern.compile("^[=+\\-@].*");

    private ContactCsvSupport() {
    }

    public record ParsedCsv(List<String> headers, List<Map<String, String>> rows) {
    }

    public static ParsedCsv parse(InputStream input, int maxBytes, int maxRows) {
        byte[] bytes;
        try {
            bytes = input.readAllBytes();
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unable to read CSV upload");
        }
        if (bytes.length == 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "CSV file is empty");
        }
        if (bytes.length > maxBytes) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "CSV exceeds maximum upload size of 10 MiB");
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        try (BufferedReader reader = new BufferedReader(new StringReader(content))) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "CSV header row is required");
            }
            List<String> headers = parseLine(headerLine);
            if (headers.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "CSV header row is required");
            }
            Map<String, Integer> headerIndex = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                String h = headers.get(i).trim();
                if (h.isEmpty()) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "CSV header cells must not be blank");
                }
                String key = h.toLowerCase(Locale.ROOT);
                if (headerIndex.containsKey(key)) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "Duplicate CSV header: " + h);
                }
                headerIndex.put(key, i);
                headers.set(i, h);
            }
            List<Map<String, String>> rows = new ArrayList<>();
            String line;
            int rowNumber = 0;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                rowNumber++;
                if (rowNumber > maxRows) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED, "CSV exceeds maximum of " + maxRows + " data rows");
                }
                List<String> cells = parseLine(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> entry : headerIndex.entrySet()) {
                    int idx = entry.getValue();
                    String value = idx < cells.size() ? cells.get(idx) : "";
                    row.put(headers.get(idx), value == null ? "" : value.trim());
                }
                rows.add(row);
            }
            if (rows.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "CSV has no data rows");
            }
            return new ParsedCsv(List.copyOf(headers), List.copyOf(rows));
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unable to parse CSV");
        }
    }

    public static String escapeFormula(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (FORMULA_RISK.matcher(value).matches()) {
            return "'" + value;
        }
        return value;
    }

    public static String toCsvLine(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(quote(escapeFormula(values.get(i) == null ? "" : values.get(i))));
        }
        return sb.toString();
    }

    static List<String> parseLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    cells.add(current.toString());
                    current.setLength(0);
                } else {
                    current.append(c);
                }
            }
        }
        cells.add(current.toString());
        return cells;
    }

    private static String quote(String value) {
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    public static String cell(Map<String, String> row, String header) {
        if (header == null || header.isBlank()) {
            return "";
        }
        for (Map.Entry<String, String> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(header.trim())) {
                return e.getValue() == null ? "" : e.getValue();
            }
        }
        return "";
    }
}
