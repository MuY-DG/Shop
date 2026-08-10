package org.muybaby.shopserver.finance.reconciliation.parser;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

final class Rfc4180Reader implements AutoCloseable {

    private final PushbackReader reader;
    private final int maxFieldLength;
    private long recordNumber;

    Rfc4180Reader(Reader reader, int maxFieldLength) {
        this.reader = new PushbackReader(reader, 1);
        this.maxFieldLength = maxFieldLength;
    }

    List<String> nextRecord() throws IOException {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean quotedField = false;
        boolean afterClosingQuote = false;
        boolean consumed = false;
        while (true) {
            int next = reader.read();
            if (next < 0) {
                if (!consumed && fields.isEmpty() && field.isEmpty()) {
                    return null;
                }
                if (inQuotes) {
                    throw malformed("unterminated quoted field");
                }
                fields.add(field.toString());
                recordNumber++;
                return List.copyOf(fields);
            }
            consumed = true;
            char ch = (char) next;
            if (inQuotes) {
                if (ch == '"') {
                    int following = reader.read();
                    if (following == '"') {
                        append(field, '"');
                    } else {
                        inQuotes = false;
                        afterClosingQuote = true;
                        if (following >= 0) {
                            reader.unread(following);
                        }
                    }
                } else {
                    append(field, ch);
                }
                continue;
            }
            if (afterClosingQuote) {
                if (ch == ',') {
                    fields.add(field.toString());
                    field.setLength(0);
                    quotedField = false;
                    afterClosingQuote = false;
                    continue;
                }
                if (ch == '\r' || ch == '\n') {
                    consumeLfAfterCr(ch);
                    fields.add(field.toString());
                    recordNumber++;
                    return List.copyOf(fields);
                }
                throw malformed("unexpected character after closing quote");
            }
            if (ch == '"' && field.isEmpty() && !quotedField) {
                inQuotes = true;
                quotedField = true;
            } else if (ch == ',') {
                fields.add(field.toString());
                field.setLength(0);
                quotedField = false;
            } else if (ch == '\r' || ch == '\n') {
                consumeLfAfterCr(ch);
                fields.add(field.toString());
                recordNumber++;
                return List.copyOf(fields);
            } else {
                append(field, ch);
            }
        }
    }

    private void consumeLfAfterCr(char ch) throws IOException {
        if (ch != '\r') {
            return;
        }
        int following = reader.read();
        if (following >= 0 && following != '\n') {
            reader.unread(following);
        }
    }

    private void append(StringBuilder field, char ch) throws IOException {
        if (field.length() >= maxFieldLength) {
            throw malformed("field exceeds configured limit");
        }
        field.append(ch);
    }

    private IOException malformed(String reason) {
        return new IOException("Malformed CSV near record " + (recordNumber + 1) + ": " + reason);
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
