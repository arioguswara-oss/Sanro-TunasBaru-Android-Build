package com.sanrotech.pos;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/**
 * ESC/POS thermal 80 mm serializer for a structured, already-rendered sales receipt.
 * Optional full-cut is requested only when cut=true; cash-drawer pulse is NEVER sent.
 * Does not read or write transactions, databases, or local web storage.
 */
final class BluetoothReceiptPrinter {
    private static final int COLUMNS = 48;
    private static final int MAX_ITEMS = 180;
    private static final int MAX_RECEIPT_BYTES = 24576;

    private BluetoothReceiptPrinter() {}

    static byte[] serializeTest(String printerName) throws JSONException {
        JSONArray content = new JSONArray();
        content.put(text("TUNAS BARU", true, "center"));
        content.put(text("SANRO POS TESTING", true, "center"));
        content.put(divider());
        content.put(text("TEST PRINT", true, "center"));
        content.put(text("Printer: " + (printerName == null || printerName.trim().isEmpty() ? "Bluetooth Device" : printerName), false, "left"));
        content.put(text("Thermal 80 mm", false, "left"));
        content.put(divider());
        content.put(text("BUKAN NOTA PENJUALAN", true, "center"));
        JSONObject receipt = new JSONObject();
        receipt.put("paperWidth", 80);
        receipt.put("reference", "BT-TEST");
        receipt.put("content", content);
        receipt.put("cut", true);
        return serialize(receipt);
    }

    private static JSONObject text(String value, boolean bold, String align) throws JSONException {
        JSONObject row = new JSONObject();
        row.put("type", "text"); row.put("text", value); row.put("bold", bold); row.put("align", align);
        return row;
    }

    private static JSONObject divider() throws JSONException {
        JSONObject row = new JSONObject(); row.put("type", "divider"); row.put("char", "-"); return row;
    }

    static byte[] serialize(JSONObject receipt) throws JSONException {
        if (receipt.optInt("paperWidth") != 80) throw new JSONException("Hanya nota thermal 80 mm yang didukung.");
        String reference = safe(receipt.optString("reference", ""), 64);
        JSONArray content = receipt.optJSONArray("content");
        if (reference.isEmpty() || content == null || content.length() < 6 || content.length() > MAX_ITEMS)
            throw new JSONException("Data nota tidak lengkap atau terlalu panjang.");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bytes(out, 0x1B, 0x40);          // Initialize ESC/POS
        bytes(out, 0x1B, 0x74, 0x00);    // CP437 (only ASCII-safe glyphs are serialized)
        bytes(out, 0x1B, 0x61, 0x00);    // Left align
        bytes(out, 0x1B, 0x45, 0x00);    // Bold off
        bytes(out, 0x1D, 0x21, 0x00);    // Standard size

        for (int i = 0; i < content.length(); i++) {
            JSONObject item = content.optJSONObject(i);
            if (item == null) throw new JSONException("Format baris nota tidak valid.");
            String type = item.optString("type", "");
            boolean bold = item.optBoolean("bold", false);
            switch (type) {
                case "text": {
                    String value = safe(item.optString("text", ""), 240);
                    if (value.isEmpty()) throw new JSONException("Teks nota kosong.");
                    String align = item.optString("align", "left");
                    if (!"left".equals(align) && !"center".equals(align) && !"right".equals(align))
                        throw new JSONException("Perataan teks tidak valid.");
                    int a = "center".equals(align) ? 1 : ("right".equals(align) ? 2 : 0);
                    bytes(out, 0x1B, 0x61, a);
                    bytes(out, 0x1B, 0x45, bold ? 1 : 0);
                    boolean large = "large".equals(item.optString("size", ""));
                    bytes(out, 0x1D, 0x21, large ? 0x11 : 0);
                    writeWrapped(out, value, large ? COLUMNS / 2 : COLUMNS);
                    bytes(out, 0x1D, 0x21, 0);
                    bytes(out, 0x1B, 0x45, 0);
                    bytes(out, 0x1B, 0x61, 0);
                    break;
                }
                case "row": {
                    String left = safe(item.optString("left", ""), 240);
                    String right = safe(item.optString("right", ""), 80);
                    if (left.isEmpty() || right.isEmpty()) throw new JSONException("Rincian nota tidak lengkap.");
                    bytes(out, 0x1B, 0x61, 0);
                    bytes(out, 0x1B, 0x45, bold ? 1 : 0);
                    writeRow(out, left, right);
                    bytes(out, 0x1B, 0x45, 0);
                    break;
                }
                case "divider": {
                    String chr = item.optString("char", "-");
                    String divider = "=".equals(chr) ? "=" : "-";
                    bytes(out, 0x1B, 0x61, 0);
                    for (int n = 0; n < COLUMNS; n++) ascii(out, divider);
                    ascii(out, "\n");
                    break;
                }
                case "feed": {
                    int lines = item.optInt("lines", 1);
                    if (lines < 1 || lines > 4) throw new JSONException("Panjang feed tidak valid.");
                    for (int n = 0; n < lines; n++) ascii(out, "\n");
                    break;
                }
                default: throw new JSONException("Jenis baris nota tidak dikenal.");
            }
            if (out.size() > MAX_RECEIPT_BYTES) throw new JSONException("Nota melebihi batas aman printer.");
        }
        bytes(out, 0x1B, 0x61, 0);
        bytes(out, 0x1B, 0x45, 0);
        bytes(out, 0x1D, 0x21, 0);
        // The cut flag must be an explicit JSON boolean. Never open the drawer.
        if (receipt.has("cut") && !(receipt.opt("cut") instanceof Boolean))
            throw new JSONException("Parameter cut harus boolean.");
        if (receipt.optBoolean("cut", false)) {
            // Feed paper beyond the print head before the cutter.
            // This is a single candidate GS V m=0 (full cut); verify on Panda.
            bytes(out, 0x0A, 0x0A, 0x0A, 0x0A);
            bytes(out, 0x1D, 0x56, 0x00);
        }
        return out.toByteArray();
    }

    private static void writeRow(ByteArrayOutputStream out, String left, String right) {
        if (right.length() >= COLUMNS - 1) {
            writeWrapped(out, left, COLUMNS);
            writeWrapped(out, right, COLUMNS);
            return;
        }
        int available = COLUMNS - right.length() - 1;
        if (left.length() > available) {
            writeWrapped(out, left, COLUMNS);
            left = "";
        }
        ascii(out, left);
        for (int i = left.length(); i < COLUMNS - right.length(); i++) ascii(out, " ");
        ascii(out, right + "\n");
    }

    private static void writeWrapped(ByteArrayOutputStream out, String text, int columns) {
        while (text.length() > columns) {
            int split = breakAt(text, columns);
            ascii(out, text.substring(0, split).trim() + "\n");
            text = text.substring(split).trim();
        }
        if (!text.isEmpty()) ascii(out, text + "\n");
    }

    private static int breakAt(String s, int limit) {
        int boundary = s.lastIndexOf(' ', Math.min(limit, s.length() - 1));
        return boundary >= limit / 2 ? boundary + 1 : limit;
    }

    private static String safe(String raw, int maxChars) throws JSONException {
        if (raw == null || raw.length() > maxChars) throw new JSONException("Panjang teks tidak valid.");
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKD)
                .replace('–', '-').replace('—', '-').replace('‘', '\'').replace('’', '\'')
                .replace('“', '"').replace('”', '"').replace('×', 'x').replace('²', '2');
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c >= 0x20 && c <= 0x7E) out.append(c);
            else if (Character.isWhitespace(c)) out.append(' ');
        }
        return out.toString().replaceAll(" +", " ").trim();
    }

    private static void ascii(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        out.write(bytes, 0, bytes.length);
    }

    private static void bytes(ByteArrayOutputStream out, int... values) {
        for (int value : values) out.write(value);
    }
}
