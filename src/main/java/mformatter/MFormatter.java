package mformatter;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * M code formatter — expands terse M source into "full" format.
 *
 * Expands command abbreviations (S→SET, W→WRITE, F→FOR, …),
 * expands special variable abbreviations ($G→$GET, $O→$ORDER, …),
 * breaks one-liners at command boundaries, and indents dotted blocks.
 *
 * Usage: java mjvm.tools.MFormatter [--break] [--indent N] [file]
 *   --break    break one-liners onto separate lines at command boundaries
 *   --indent N indent size per dot level (default 2)
 *   file       input file (default: stdin)
 *
 * Output goes to stdout.
 */
public final class MFormatter {

    /** Command abbreviation → full name (ISO 8.1.1). */
    private static final Map<String, String> CMD_EXPAND = new LinkedHashMap<>();
    static {
        // Single-letter abbreviations (longest match wins)
        CMD_EXPAND.put("B", "BREAK");
        CMD_EXPAND.put("C", "CLOSE");
        CMD_EXPAND.put("D", "DO");
        CMD_EXPAND.put("E", "ELSE");
        CMD_EXPAND.put("F", "FOR");
        CMD_EXPAND.put("G", "GOTO");
        CMD_EXPAND.put("H", "HALT");
        CMD_EXPAND.put("I", "IF");
        CMD_EXPAND.put("J", "JOB");
        CMD_EXPAND.put("K", "KILL");
        CMD_EXPAND.put("L", "LOCK");
        CMD_EXPAND.put("M", "MERGE");
        CMD_EXPAND.put("N", "NEW");
        CMD_EXPAND.put("O", "OPEN");
        CMD_EXPAND.put("Q", "QUIT");
        CMD_EXPAND.put("R", "READ");
        CMD_EXPAND.put("S", "SET");
        CMD_EXPAND.put("T", "TSTART");
        CMD_EXPAND.put("U", "USE");
        CMD_EXPAND.put("V", "VIEW");
        CMD_EXPAND.put("W", "WRITE");
        CMD_EXPAND.put("X", "XECUTE");
        // Two-letter abbreviations
        CMD_EXPAND.put("TC", "TCOMMIT");
        CMD_EXPAND.put("TR", "TROLLBACK");
        // Full names (identity, for detection)
        for (String full : new String[]{"BREAK","CLOSE","DO","ELSE","FOR","GOTO",
                "HALT","HANG","IF","JOB","KILL","LOCK","MERGE","NEW","OPEN",
                "QUIT","READ","SET","TSTART","TCOMMIT","TROLLBACK","USE","VIEW",
                "WRITE","XECUTE"}) {
            CMD_EXPAND.put(full, full);
        }
    }

    /** Special variable abbreviation → full name. */
    private static final Map<String, String> SV_EXPAND = new LinkedHashMap<>();
    static {
        SV_EXPAND.put("C", "CHAR");
        SV_EXPAND.put("D", "DATA");
        SV_EXPAND.put("E", "EXTRACT");
        SV_EXPAND.put("F", "FIND");
        SV_EXPAND.put("G", "GET");
        SV_EXPAND.put("H", "HOROLOG");
        SV_EXPAND.put("I", "INCREMENT");
        SV_EXPAND.put("J", "JOB");
        SV_EXPAND.put("K", "KEY");
        SV_EXPAND.put("L", "LENGTH");
        SV_EXPAND.put("NA", "NAME");
        SV_EXPAND.put("O", "ORDER");
        SV_EXPAND.put("P", "PIECE");
        SV_EXPAND.put("QL", "QLENGTH");
        SV_EXPAND.put("QS", "QSUBSCRIPT");
        SV_EXPAND.put("Q", "QUERY");
        SV_EXPAND.put("R", "RANDOM");
        SV_EXPAND.put("S", "STACK");
        SV_EXPAND.put("ST", "TEXT");
        SV_EXPAND.put("T", "TEXT");
        SV_EXPAND.put("V", "VIEW");
        SV_EXPAND.put("X", "X");
        SV_EXPAND.put("Y", "Y");
        SV_EXPAND.put("Z", "Z");
        // Full names (identity)
        for (String full : new String[]{"ASCII","CHAR","DATA","EXTRACT","FIND",
                "GET","HOROLOG","INCREMENT","JOB","KEY","LENGTH","NAME","ORDER",
                "PIECE","QLENGTH","QSUBSCRIPT","QUERY","RANDOM","STACK","TEXT",
                "VIEW","X","Y","Z"}) {
            SV_EXPAND.put(full, full);
        }
    }

    private final int indentSize;
    private final boolean breakLines;

    public MFormatter(int indentSize, boolean breakLines) {
        this.indentSize = indentSize;
        this.breakLines = breakLines;
    }

    /**
     * Format M source code. Expands commands, special variables,
     * optionally breaks lines, and indents dotted blocks.
     */
    public String format(String source) {
        String[] lines = source.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (i > 0) out.append('\n');
            formatLine(line, out);
        }
        return out.toString();
    }

    void formatLine(String line, StringBuilder out) {
        // 1. Extract dot prefix (block depth)
        int depth = 0;
        int pos = 0;
        while (pos < line.length() && line.charAt(pos) == '.') {
            // Dots followed by digits are part of a number, not depth
            if (pos + 1 < line.length() && Character.isDigit(line.charAt(pos + 1))) break;
            depth++;
            pos++;
        }

        // 2. Emit indentation
        for (int j = 0; j < depth * indentSize; j++) out.append(' ');

        // 3. Extract label (if any) — alphanumeric + % at start of line (after dots)
        String rest = line.substring(pos);
        int labelEnd = 0;
        while (labelEnd < rest.length() &&
               (Character.isLetterOrDigit(rest.charAt(labelEnd)) ||
                rest.charAt(labelEnd) == '%' || rest.charAt(labelEnd) == '^')) {
            labelEnd++;
        }
        // Label must be followed by space, tab, or end (not =, (, etc.)
        if (labelEnd > 0 && labelEnd < rest.length()) {
            char next = rest.charAt(labelEnd);
            if (next != ' ' && next != '\t' && next != '=') {
                labelEnd = 0; // not a label
            }
        }
        if (labelEnd > 0) {
            out.append(rest, 0, labelEnd);
            rest = rest.substring(labelEnd);
            // Preserve space after label
            int sp = 0;
            while (sp < rest.length() && (rest.charAt(sp) == ' ' || rest.charAt(sp) == '\t')) sp++;
            if (sp > 0) {
                out.append(' ');
                rest = rest.substring(sp);
            }
        }

        // 4. Expand commands and special variables in the body
        String body = expandCommands(rest);
        body = expandSpecialVars(body);

        // 5. Optionally break lines at command boundaries
        if (breakLines) {
            body = breakAtCommands(body);
        }

        out.append(body);
    }

    /**
     * Expand command abbreviations. Commands are separated by spaces
     * and appear at command boundaries (after label, after previous command).
     */
    String expandCommands(String body) {
        if (body.isEmpty()) return body;
        StringBuilder sb = new StringBuilder();
        // Scan character by character, tracking command boundaries
        int i = 0;
        boolean atCmdStart = true;
        while (i < body.length()) {
            char c = body.charAt(i);
            // $$extrinsic call — copy through, don't expand
            if (c == '$' && i + 1 < body.length() && body.charAt(i + 1) == '$') {
                int j = i + 2;
                while (j < body.length() && (Character.isLetterOrDigit(body.charAt(j)) || body.charAt(j) == '%')) j++;
                // Skip past the function name and its argument list
                if (j < body.length() && body.charAt(j) == '(') {
                    int depth = 0;
                    while (j < body.length()) {
                        if (body.charAt(j) == '(') depth++;
                        if (body.charAt(j) == ')') { depth--; if (depth == 0) { j++; break; } }
                        j++;
                    }
                }
                sb.append(body, i, j);
                i = j;
                atCmdStart = false;
                continue;
            }
            // $special variable — copy through
            if (c == '$' && i + 1 < body.length() && body.charAt(i + 1) != '$') {
                int j = i + 1;
                while (j < body.length() && Character.isLetterOrDigit(body.charAt(j))) j++;
                // Skip argument list if present
                if (j < body.length() && body.charAt(j) == '(') {
                    int depth = 0;
                    while (j < body.length()) {
                        if (body.charAt(j) == '(') depth++;
                        if (body.charAt(j) == ')') { depth--; if (depth == 0) { j++; break; } }
                        j++;
                    }
                }
                sb.append(body, i, j);
                i = j;
                atCmdStart = false;
                continue;
            }
            // Command start: letter at command boundary
            if (atCmdStart && Character.isLetter(c)) {
                // Extract word (letters only) and check if it's a command
                int j = i;
                while (j < body.length() && Character.isLetter(body.charAt(j))) j++;
                String word = body.substring(i, j);
                // Check character AFTER the word — must be space/tab/EOL for command
                boolean isCmd = (j >= body.length() || body.charAt(j) == ' ' || body.charAt(j) == '\t');
                String expanded = isCmd ? expandOneCommand(word) : word;
                sb.append(expanded);
                // Preserve any non-letter chars after the word
                i = j;
                atCmdStart = false;
                // After a command word, we need a space before arguments
                // But M allows S X=1 (space between command and args)
                // Just continue scanning — the space will be copied naturally
                continue;
            }
            // Space after command → next word could be a command
            if (c == ' ' || c == '\t') {
                sb.append(c);
                i++;
                // Check if next word is a command
                if (i < body.length() && Character.isLetter(body.charAt(i))) {
                    atCmdStart = true;
                }
                continue;
            }
            // Any other char — copy
            sb.append(c);
            i++;
            atCmdStart = false;
        }
        return sb.toString();
    }

    int countLeadingCmdChars(String tok) {
        int n = 0;
        while (n < tok.length() && Character.isLetter(tok.charAt(n))) n++;
        return Math.min(n, 7); // max command name length
    }

    /** Expand a single command abbreviation if known. */
    String expandOneCommand(String word) {
        String up = word.toUpperCase();
        // Try longest match first (TC, TR before T)
        for (int len = Math.min(up.length(), 7); len >= 1; len--) {
            String prefix = up.substring(0, len);
            String expanded = CMD_EXPAND.get(prefix);
            if (expanded != null) {
                // Verify it's a command boundary: next char must be space, tab, or EOL
                // NOT = ( , " etc. — those mean it's a variable/expression, not a command
                if (len == up.length()) return expanded;
                char next = up.charAt(len);
                if (next == ' ' || next == '\t') return expanded;
                return word; // continues past abbreviation — not a command
            }
        }
        return word;
    }

    /**
     * Expand special variable abbreviations: $G→$GET, $O→$ORDER, etc.
     * Pattern: $NAME or $NAME( — expand NAME if it's a known abbreviation.
     */
    String expandSpecialVars(String body) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < body.length()) {
            if (body.charAt(i) == '$' && i + 1 < body.length() && body.charAt(i + 1) == '$') {
                // $$extrinsic — copy through
                sb.append("$$");
                i += 2;
                continue;
            }
            if (body.charAt(i) == '$' && i + 1 < body.length()) {
                // $NAME — extract the name
                int j = i + 1;
                // Z-prefix: $Zxxx — extract full name
                if (body.charAt(j) == 'Z' || body.charAt(j) == 'z') {
                    while (j < body.length() && Character.isLetterOrDigit(body.charAt(j))) j++;
                    sb.append(body, i, j);
                    i = j;
                    continue;
                }
                // Standard $ — extract name letters
                while (j < body.length() && Character.isLetter(body.charAt(j))) j++;
                String name = body.substring(i + 1, j);
                String up = name.toUpperCase();
                // Try longest abbreviation match
                String expanded = expandOneSvName(up);
                sb.append('$').append(expanded);
                i = j;
                continue;
            }
            sb.append(body.charAt(i));
            i++;
        }
        return sb.toString();
    }

    /** Expand a special variable name abbreviation. */
    String expandOneSvName(String up) {
        // Try longest match first (QL, QS before Q)
        for (int len = Math.min(up.length(), 8); len >= 1; len--) {
            String prefix = up.substring(0, len);
            String expanded = SV_EXPAND.get(prefix);
            if (expanded != null && (len == up.length() || !Character.isLetterOrDigit(up.charAt(len)))) {
                return expanded;
            }
        }
        return up;
    }

    /**
     * Break one-liners at command boundaries. Each command goes on its own line.
     * Preserves dot depth for continuation.
     */
    String breakAtCommands(String body) {
        // Find command boundaries and insert newlines + indentation
        // This is a simplified version — full version needs expression parsing
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < body.length()) {
            char c = body.charAt(i);
            // Check if this is a command start: preceded by space and is a known command
            if (i > 0 && (body.charAt(i - 1) == ' ' || body.charAt(i - 1) == '\t')) {
                // Try to match a command abbreviation
                String rest = body.substring(i);
                String cmd = extractCmdAtStart(rest);
                if (cmd != null && cmd.length() < rest.length()) {
                    // Found a command boundary — break here
                    sb.append('\n');
                    // Preserve original indentation (dots)
                    // For now, just indent by 2 spaces
                    sb.append("  ");
                    sb.append(body, i, i + cmd.length());
                    i += cmd.length();
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /** Try to extract a command name at the start of the string. */
    String extractCmdAtStart(String s) {
        if (s.isEmpty()) return null;
        String up = s.toUpperCase();
        // Try longest match first
        for (int len = Math.min(up.length(), 7); len >= 1; len--) {
            String prefix = up.substring(0, len);
            if (CMD_EXPAND.containsKey(prefix)) {
                // Must be at a boundary: next char is space, tab, or EOL
                // NOT = ( , " etc. — those mean it's an argument
                if (len == up.length()) return prefix;
                char next = up.charAt(len);
                if (next == ' ' || next == '\t') return prefix;
                return null;
            }
        }
        return null;
    }

    // --- Organize: structural view ---

    /** Extract tags (labels at column 0) and their metadata. */
    static class Tag {
        String name;
        String params;
        int startLine;
        int endLine;
        List<String> calls = new ArrayList<>();
        List<String> extrinsic = new ArrayList<>();
        Set<String> globalsRead = new LinkedHashSet<>();
        Set<String> globalsWritten = new LinkedHashSet<>();
        String comment;
    }

    /** Build organizational view: tags, calls, globals, with full-format code. */
    public String organize(String source) {
        String[] lines = source.split("\n", -1);
        List<Tag> tags = new ArrayList<>();
        Tag current = null;

        // Pass 1: find tags
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int labelEnd = 0;
            while (labelEnd < line.length() &&
                   (Character.isLetterOrDigit(line.charAt(labelEnd)) ||
                    line.charAt(labelEnd) == '%' || line.charAt(labelEnd) == '^')) {
                labelEnd++;
            }
            if (labelEnd == 0) continue;
            if (labelEnd < line.length()) {
                char next = line.charAt(labelEnd);
                if (next != ' ' && next != '\t' && next != '(') continue;
            }
            String label = line.substring(0, labelEnd);
            String rest = line.substring(labelEnd).strip();
            String params = "";
            int paren = label.indexOf('(');
            String tagLabel = label;
            if (paren > 0) {
                tagLabel = label.substring(0, paren);
                params = label.substring(paren);
            }
            String comment = "";
            int semi = rest.indexOf(';');
            if (semi >= 0) comment = rest.substring(semi + 1).strip();

            current = new Tag();
            current.name = tagLabel;
            current.params = params;
            current.comment = comment;
            current.startLine = i;
            tags.add(current);
        }

        for (int i = 0; i < tags.size(); i++) {
            tags.get(i).endLine = (i + 1 < tags.size()) ? tags.get(i + 1).startLine - 1 : lines.length - 1;
        }

        // Pass 2: extract metadata
        for (Tag tag : tags) {
            for (int i = tag.startLine; i <= tag.endLine; i++) {
                String line = lines[i];
                int semi = line.indexOf(';');
                if (semi >= 0) line = line.substring(0, semi);
                extractMetadata(line, tag);
            }
        }

        // Generate output
        StringBuilder sb = new StringBuilder();
        String routineName = tags.isEmpty() ? "(routine)" : tags.get(0).name;
        sb.append("=== ").append(routineName).append(" — Organization ===\n\n");

        sb.append("Tags:\n");
        for (Tag tag : tags) {
            sb.append("  ").append(tag.name);
            if (!tag.params.isEmpty()) sb.append(tag.params);
            if (!tag.comment.isEmpty()) sb.append("  // ").append(tag.comment);
            sb.append('\n');
            if (!tag.calls.isEmpty())
                sb.append("    calls: ").append(String.join(", ", tag.calls)).append('\n');
            if (!tag.extrinsic.isEmpty())
                sb.append("    extrinsic: ").append(String.join(", ", tag.extrinsic)).append('\n');
            if (!tag.globalsRead.isEmpty())
                sb.append("    reads: ").append(String.join(", ", tag.globalsRead)).append('\n');
            if (!tag.globalsWritten.isEmpty())
                sb.append("    writes: ").append(String.join(", ", tag.globalsWritten)).append('\n');
        }

        sb.append("\nCall graph:\n");
        boolean anyCalls = false;
        for (Tag tag : tags) {
            for (String target : tag.calls) {
                sb.append("  ").append(tag.name).append(" \u2192 ").append(target).append('\n');
                anyCalls = true;
            }
            for (String target : tag.extrinsic) {
                sb.append("  ").append(tag.name).append(" \u2192 $$").append(target).append('\n');
                anyCalls = true;
            }
        }
        if (!anyCalls) sb.append("  (no inter-tag calls)\n");

        sb.append("\nGlobal access:\n");
        Map<String, List<String>> globalReaders = new LinkedHashMap<>();
        Map<String, List<String>> globalWriters = new LinkedHashMap<>();
        for (Tag tag : tags) {
            for (String g : tag.globalsRead)
                globalReaders.computeIfAbsent(g, k -> new ArrayList<>()).add(tag.name);
            for (String g : tag.globalsWritten)
                globalWriters.computeIfAbsent(g, k -> new ArrayList<>()).add(tag.name);
        }
        for (String g : globalReaders.keySet()) {
            sb.append("  ").append(g).append(" (read by ").append(String.join(", ", globalReaders.get(g))).append(")");
            if (globalWriters.containsKey(g))
                sb.append(" (written by ").append(String.join(", ", globalWriters.get(g))).append(")");
            sb.append('\n');
        }
        for (String g : globalWriters.keySet()) {
            if (!globalReaders.containsKey(g))
                sb.append("  ").append(g).append(" (written by ").append(String.join(", ", globalWriters.get(g))).append(")\n");
        }

        sb.append("\nCode:\n");
        MFormatter fmt = new MFormatter(indentSize, false);
        for (Tag tag : tags) {
            sb.append("\n").append(tag.name).append(tag.params.isEmpty() ? "" : tag.params).append(":\n");
            for (int i = tag.startLine; i <= tag.endLine; i++)
                sb.append("  ").append(fmt.format(lines[i]).strip()).append('\n');
        }

        return sb.toString();
    }

    void extractMetadata(String line, Tag tag) {
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            // DO target: D TAG
            if ((c == 'D' || c == 'd') &&
                (i == 0 || line.charAt(i-1) == ' ' || line.charAt(i-1) == '\t') &&
                (i+1 >= line.length() || line.charAt(i+1) == ' ' || line.charAt(i+1) == '\t')) {
                int j = i + 1;
                while (j < line.length() && (line.charAt(j) == ' ' || line.charAt(j) == '\t')) j++;
                int start = j;
                while (j < line.length() && (Character.isLetterOrDigit(line.charAt(j)) ||
                       line.charAt(j) == '%' || line.charAt(j) == '^')) j++;
                if (j > start) {
                    String target = line.substring(start, j);
                    if (!tag.calls.contains(target)) tag.calls.add(target);
                }
                i = j;
                continue;
            }
            // Extrinsic: $$TAG
            if (c == '$' && i+1 < line.length() && line.charAt(i+1) == '$') {
                int j = i + 2;
                int start = j;
                while (j < line.length() && (Character.isLetterOrDigit(line.charAt(j)) ||
                       line.charAt(j) == '%' || line.charAt(j) == '^')) j++;
                if (j > start) {
                    String target = line.substring(start, j);
                    if (!tag.extrinsic.contains(target)) tag.extrinsic.add(target);
                }
                i = j;
                continue;
            }
            // Global: ^NAME
            if (c == '^') {
                int j = i + 1;
                int start = j;
                while (j < line.length() && (Character.isLetterOrDigit(line.charAt(j)) ||
                       line.charAt(j) == '%')) j++;
                if (j > start) {
                    String gname = "^" + line.substring(start, j);
                    boolean isWrite = isGlobalWrite(line, i);
                    if (isWrite) {
                        if (!tag.globalsWritten.contains(gname)) tag.globalsWritten.add(gname);
                    } else {
                        if (!tag.globalsRead.contains(gname)) tag.globalsRead.add(gname);
                    }
                }
                i = j;
                continue;
            }
            i++;
        }
    }

    boolean isGlobalWrite(String line, int pos) {
        String before = line.substring(0, pos).toUpperCase();
        return before.contains("SET ") || before.endsWith(" S ") || before.equals("S") ||
               before.contains("KILL ") || before.endsWith(" K ") || before.equals("K");
    }

    // --- Attribute & Cross-Reference Generator (IBM PL/I Checkout style) ---

    /** Symbol attributes: name, kind, scope, definition, references. */
    static class Symbol {
        String name;
        String kind;        // "local", "global", "param", "tag", "extrinsic"
        String scope;       // tag name where defined, or "(routine)"
        int defLine;        // line number of definition (1-based)
        List<Integer> refs = new ArrayList<>(); // line numbers of references
        String attributes = "";  // additional attributes (params, type, etc.)
    }

    /**
     * Generate attribute & cross-reference listing.
     *
     * For each symbol (variable, tag, global, extrinsic):
     *   - Attributes: kind, scope, definition line
     *   - Cross-reference: every line where it appears
     *
     * Output format follows IBM PL/I Checkout Compiler conventions:
     *   SYMBOL          KIND      SCOPE    DEF   REFS
     *   %AC             PARAM     DEP      19    20,22,23
     *   ^BANK           GLOBAL    (global)  22   23,24,25,...
     */
    public String xref(String source) {
        String[] lines = source.split("\n", -1);
        List<Tag> tags = new ArrayList<>();
        Tag current = null;
        Map<String, Symbol> symbols = new LinkedHashMap<>();

        // Pass 1: find tags and their line ranges
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int labelEnd = 0;
            while (labelEnd < line.length() &&
                   (Character.isLetterOrDigit(line.charAt(labelEnd)) ||
                    line.charAt(labelEnd) == '%' || line.charAt(labelEnd) == '^')) {
                labelEnd++;
            }
            if (labelEnd == 0) continue;
            if (labelEnd < line.length()) {
                char next = line.charAt(labelEnd);
                if (next != ' ' && next != '\t' && next != '(') continue;
            }
            // Extract full label including parenthesized params: DEP(%AC,%A)
            String label = line.substring(0, labelEnd);
            String params = "";
            String tagLabel = label;
            int paren = -1;
            // Check if next char after labelEnd is '(' — parameterized tag
            if (labelEnd < line.length() && line.charAt(labelEnd) == '(') {
                paren = labelEnd;
                int close = line.indexOf(')', labelEnd);
                if (close > 0) {
                    params = line.substring(labelEnd, close + 1);
                    tagLabel = line.substring(0, labelEnd);
                }
            }
            String rest = line.substring(labelEnd).strip();
            if (paren > 0) rest = line.substring(line.indexOf(')', labelEnd) + 1).strip();

            current = new Tag();
            current.name = tagLabel;
            current.params = params;
            current.startLine = i;
            tags.add(current);

            // Register tag symbol
            Symbol tagSym = new Symbol();
            tagSym.name = tagLabel;
            tagSym.kind = "TAG";
            tagSym.scope = "(routine)";
            tagSym.defLine = i + 1;
            tagSym.attributes = params.isEmpty() ? "" : "params=" + params;
            symbols.put("TAG:" + tagLabel, tagSym);

            // Register parameter symbols
            if (paren > 0 && !params.isEmpty()) {
                String paramStr = params.substring(1, params.length() - 1); // strip parens
                for (String p : paramStr.split(",")) {
                    p = p.trim();
                    if (!p.isEmpty()) {
                        Symbol ps = new Symbol();
                        ps.name = p;
                        ps.kind = "PARAM";
                        ps.scope = tagLabel;
                        ps.defLine = i + 1;
                        ps.attributes = "of " + tagLabel;
                        symbols.put("PARAM:" + tagLabel + ":" + p, ps);
                    }
                }
            }
        }

        for (int i = 0; i < tags.size(); i++) {
            tags.get(i).endLine = (i + 1 < tags.size()) ? tags.get(i + 1).startLine - 1 : lines.length - 1;
        }

        // Pass 2: find all symbol references
        for (Tag tag : tags) {
            for (int i = tag.startLine; i <= tag.endLine; i++) {
                String line = lines[i];
                int lineNum = i + 1; // 1-based
                // Strip comment for symbol analysis
                String code = line;
                int semi = line.indexOf(';');
                if (semi >= 0) code = line.substring(0, semi);

                // Find globals: ^NAME
                int pos = 0;
                while (pos < code.length()) {
                    if (code.charAt(pos) == '^') {
                        int j = pos + 1;
                        int start = j;
                        while (j < code.length() && (Character.isLetterOrDigit(code.charAt(j)) ||
                               code.charAt(j) == '%')) j++;
                        if (j > start) {
                            String gname = "^" + code.substring(start, j);
                            Symbol sym = symbols.computeIfAbsent("GLOBAL:" + gname, k -> {
                                Symbol s = new Symbol();
                                s.name = gname;
                                s.kind = "GLOBAL";
                                s.scope = "(global)";
                                s.defLine = -1;
                                return s;
                            });
                            if (!sym.refs.contains(lineNum)) sym.refs.add(lineNum);
                        }
                        pos = j;
                        continue;
                    }
                    // Find locals: %NAME
                    if (code.charAt(pos) == '%' && pos > 0 &&
                        (Character.isLetterOrDigit(code.charAt(pos-1)) || code.charAt(pos-1) == '%' ||
                         code.charAt(pos-1) == '^')) {
                        // % preceded by identifier char — part of subscript, skip
                        pos++;
                        continue;
                    }
                    if (code.charAt(pos) == '%') {
                        int j = pos + 1;
                        int start = j;
                        while (j < code.length() && (Character.isLetterOrDigit(code.charAt(j)) ||
                               code.charAt(j) == '%')) j++;
                        if (j > start) {
                            String lname = "%" + code.substring(start, j);
                            // Check if it's a parameter of this tag
                            String key = "PARAM:" + tag.name + ":" + lname;
                            Symbol sym = symbols.get(key);
                            if (sym == null) {
                                sym = symbols.computeIfAbsent("LOCAL:" + tag.name + ":" + lname, k -> {
                                    Symbol s = new Symbol();
                                    s.name = lname;
                                    s.kind = "LOCAL";
                                    s.scope = tag.name;
                                    s.defLine = lineNum;
                                    return s;
                                });
                            }
                            if (!sym.refs.contains(lineNum)) sym.refs.add(lineNum);
                        }
                        pos = j;
                        continue;
                    }
                    // Find extrinsic: $$NAME
                    if (code.charAt(pos) == '$' && pos+1 < code.length() && code.charAt(pos+1) == '$') {
                        int j = pos + 2;
                        int start = j;
                        while (j < code.length() && (Character.isLetterOrDigit(code.charAt(j)) ||
                               code.charAt(j) == '%' || code.charAt(j) == '^')) j++;
                        if (j > start) {
                            String ename = code.substring(start, j);
                            Symbol sym = symbols.computeIfAbsent("EXT:" + ename, k -> {
                                Symbol s = new Symbol();
                                s.name = "$$" + ename;
                                s.kind = "EXTRINSIC";
                                s.scope = "(routine)";
                                s.defLine = -1;
                                return s;
                            });
                            if (!sym.refs.contains(lineNum)) sym.refs.add(lineNum);
                        }
                        pos = j;
                        continue;
                    }
                    pos++;
                }

                // Find DO/GOTO targets (tag references)
                pos = 0;
                while (pos < code.length()) {
                    char c = code.charAt(pos);
                    if ((c == 'D' || c == 'd' || c == 'G' || c == 'g') &&
                        (pos == 0 || code.charAt(pos-1) == ' ' || code.charAt(pos-1) == '\t') &&
                        (pos+1 >= code.length() || code.charAt(pos+1) == ' ' || code.charAt(pos+1) == '\t')) {
                        int j = pos + 1;
                        while (j < code.length() && (code.charAt(j) == ' ' || code.charAt(j) == '\t')) j++;
                        int start = j;
                        while (j < code.length() && (Character.isLetterOrDigit(code.charAt(j)) ||
                               code.charAt(j) == '%' || code.charAt(j) == '^')) j++;
                        if (j > start) {
                            String target = code.substring(start, j);
                            String key = "TAG:" + target;
                            Symbol sym = symbols.get(key);
                            if (sym != null && !sym.refs.contains(lineNum)) {
                                sym.refs.add(lineNum);
                            }
                        }
                        pos = j;
                        continue;
                    }
                    pos++;
                }
            }
        }

        // Generate output
        StringBuilder sb = new StringBuilder();
        String routineName = tags.isEmpty() ? "(routine)" : tags.get(0).name;
        sb.append("=== ").append(routineName).append(" — Attribute & Cross-Reference ===\n\n");

        // Column header
        sb.append(String.format("%-20s %-10s %-12s %5s  %s%n",
            "SYMBOL", "KIND", "SCOPE", "DEF", "CROSS-REFERENCE (line numbers)"));
        sb.append("-".repeat(80)).append('\n');

        // Sort: TAGs first, then PARAMs, LOCALs, GLOBALs, EXTRINSICs
        List<Symbol> sorted = new ArrayList<>(symbols.values());
        sorted.sort((a, b) -> {
            int ka = kindOrder(a.kind), kb = kindOrder(b.kind);
            if (ka != kb) return ka - kb;
            return a.name.compareTo(b.name);
        });

        for (Symbol sym : sorted) {
            String defStr = sym.defLine > 0 ? String.valueOf(sym.defLine) : "—";
            String refsStr = sym.refs.isEmpty() ? "(none)" :
                sym.refs.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
            String attrs = sym.attributes.isEmpty() ? "" : "  [" + sym.attributes + "]";
            sb.append(String.format("%-20s %-10s %-12s %5s  %s%s%n",
                sym.name, sym.kind, sym.scope, defStr, refsStr, attrs));
        }

        // Summary
        sb.append("\n");
        long nTags = symbols.values().stream().filter(s -> s.kind.equals("TAG")).count();
        long nParams = symbols.values().stream().filter(s -> s.kind.equals("PARAM")).count();
        long nLocals = symbols.values().stream().filter(s -> s.kind.equals("LOCAL")).count();
        long nGlobals = symbols.values().stream().filter(s -> s.kind.equals("GLOBAL")).count();
        long nExtr = symbols.values().stream().filter(s -> s.kind.equals("EXTRINSIC")).count();
        sb.append(String.format("Summary: %d tags, %d params, %d locals, %d globals, %d extrinsics%n",
            nTags, nParams, nLocals, nGlobals, nExtr));

        return sb.toString();
    }

    static int kindOrder(String kind) {
        switch (kind) {
            case "TAG": return 0;
            case "PARAM": return 1;
            case "LOCAL": return 2;
            case "GLOBAL": return 3;
            case "EXTRINSIC": return 4;
            default: return 5;
        }
    }

    // --- Database output ---

    /**
     * Write formatted code, tags, symbols, and cross-references to a SQLite database.
     * Tables: source, tags, symbols, xref.
     */
    public void writeDatabase(String source, String dbPath) throws Exception {
        Class.forName("org.sqlite.JDBC");
        try (var conn = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            try (var st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS source");
                st.execute("DROP TABLE IF EXISTS tags");
                st.execute("DROP TABLE IF EXISTS symbols");
                st.execute("DROP TABLE IF EXISTS xref");
                st.execute("DROP TABLE IF EXISTS formatted");
                st.execute("""
                    CREATE TABLE source (
                        id INTEGER PRIMARY KEY,
                        line_num INTEGER,
                        text TEXT,
                        depth INTEGER
                    )""");
                st.execute("""
                    CREATE TABLE formatted (
                        id INTEGER PRIMARY KEY,
                        line_num INTEGER,
                        text TEXT
                    )""");
                st.execute("""
                    CREATE TABLE tags (
                        id INTEGER PRIMARY KEY,
                        name TEXT,
                        params TEXT,
                        comment TEXT,
                        start_line INTEGER,
                        end_line INTEGER
                    )""");
                st.execute("""
                    CREATE TABLE symbols (
                        id INTEGER PRIMARY KEY,
                        name TEXT,
                        kind TEXT,
                        scope TEXT,
                        def_line INTEGER,
                        attributes TEXT
                    )""");
                st.execute("""
                    CREATE TABLE xref (
                        id INTEGER PRIMARY KEY,
                        symbol_id INTEGER,
                        line_num INTEGER,
                        FOREIGN KEY(symbol_id) REFERENCES symbols(id)
                    )""");
            }

            // Store source lines
            String[] lines = source.split("\n", -1);
            try (var ps = conn.prepareStatement("INSERT INTO source(line_num,text,depth) VALUES(?,?,?)")) {
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i];
                    int depth = 0;
                    while (depth < line.length() && line.charAt(depth) == '.') {
                        if (depth + 1 < line.length() && Character.isDigit(line.charAt(depth + 1))) break;
                        depth++;
                    }
                    ps.setInt(1, i + 1);
                    ps.setString(2, line);
                    ps.setInt(3, depth);
                    ps.executeUpdate();
                }
            }

            // Store formatted code
            MFormatter fmt = new MFormatter(indentSize, false);
            try (var ps = conn.prepareStatement("INSERT INTO formatted(line_num,text) VALUES(?,?)")) {
                for (int i = 0; i < lines.length; i++) {
                    ps.setInt(1, i + 1);
                    ps.setString(2, fmt.format(lines[i]));
                    ps.executeUpdate();
                }
            }

            // Store tags
            List<Tag> tags = new ArrayList<>();
            Tag current = null;
            Map<String, Symbol> symbols = new LinkedHashMap<>();

            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                int labelEnd = 0;
                while (labelEnd < line.length() &&
                       (Character.isLetterOrDigit(line.charAt(labelEnd)) ||
                        line.charAt(labelEnd) == '%' || line.charAt(labelEnd) == '^')) {
                    labelEnd++;
                }
                if (labelEnd == 0) continue;
                if (labelEnd < line.length()) {
                    char next = line.charAt(labelEnd);
                    if (next != ' ' && next != '\t' && next != '(') continue;
                }
                String label = line.substring(0, labelEnd);
                String params = "";
                String tagLabel = label;
                int paren = -1;
                if (labelEnd < line.length() && line.charAt(labelEnd) == '(') {
                    paren = labelEnd;
                    int close = line.indexOf(')', labelEnd);
                    if (close > 0) {
                        params = line.substring(labelEnd, close + 1);
                        tagLabel = line.substring(0, labelEnd);
                    }
                }
                String rest = line.substring(labelEnd).strip();
                if (paren > 0) rest = line.substring(line.indexOf(')', labelEnd) + 1).strip();
                String comment = "";
                int semi = rest.indexOf(';');
                if (semi >= 0) comment = rest.substring(semi + 1).strip();

                current = new Tag();
                current.name = tagLabel;
                current.params = params;
                current.comment = comment;
                current.startLine = i;
                tags.add(current);
            }
            for (int i = 0; i < tags.size(); i++) {
                tags.get(i).endLine = (i + 1 < tags.size()) ? tags.get(i + 1).startLine - 1 : lines.length - 1;
            }

            try (var ps = conn.prepareStatement("INSERT INTO tags(name,params,comment,start_line,end_line) VALUES(?,?,?,?,?)")) {
                for (Tag tag : tags) {
                    ps.setString(1, tag.name);
                    ps.setString(2, tag.params);
                    ps.setString(3, tag.comment);
                    ps.setInt(4, tag.startLine + 1);
                    ps.setInt(5, tag.endLine + 1);
                    ps.executeUpdate();
                }
            }

            // Store symbols and xref
            // Reuse the xref logic
            for (Tag tag : tags) {
                for (int i = tag.startLine; i <= tag.endLine; i++) {
                    String line = lines[i];
                    String code = line;
                    int semi = line.indexOf(';');
                    if (semi >= 0) code = line.substring(0, semi);
                    extractMetadata(code, tag);
                }
            }

            // Build symbols map (same as xref method)
            for (Tag tag : tags) {
                Symbol tagSym = new Symbol();
                tagSym.name = tag.name;
                tagSym.kind = "TAG";
                tagSym.scope = "(routine)";
                tagSym.defLine = tag.startLine + 1;
                tagSym.attributes = tag.params.isEmpty() ? "" : "params=" + tag.params;
                symbols.put("TAG:" + tag.name, tagSym);

                if (!tag.params.isEmpty()) {
                    String paramStr = tag.params.substring(1, tag.params.length() - 1);
                    for (String p : paramStr.split(",")) {
                        p = p.trim();
                        if (!p.isEmpty()) {
                            Symbol ps = new Symbol();
                            ps.name = p;
                            ps.kind = "PARAM";
                            ps.scope = tag.name;
                            ps.defLine = tag.startLine + 1;
                            ps.attributes = "of " + tag.name;
                            symbols.put("PARAM:" + tag.name + ":" + p, ps);
                        }
                    }
                }
            }

            // Collect references from extractMetadata results
            for (Tag tag : tags) {
                for (String call : tag.calls) {
                    Symbol sym = symbols.get("TAG:" + call);
                    if (sym != null) sym.refs.add(tag.startLine + 1);
                }
                for (String ext : tag.extrinsic) {
                    Symbol sym = symbols.computeIfAbsent("EXT:" + ext, k -> {
                        Symbol s = new Symbol();
                        s.name = "$$" + ext;
                        s.kind = "EXTRINSIC";
                        s.scope = "(routine)";
                        s.defLine = -1;
                        return s;
                    });
                    sym.refs.add(tag.startLine + 1);
                }
                for (String g : tag.globalsRead) {
                    Symbol sym = symbols.computeIfAbsent("GLOBAL:" + g, k -> {
                        Symbol s = new Symbol();
                        s.name = g;
                        s.kind = "GLOBAL";
                        s.scope = "(global)";
                        s.defLine = -1;
                        return s;
                    });
                    sym.refs.add(tag.startLine + 1);
                }
                for (String g : tag.globalsWritten) {
                    Symbol sym = symbols.computeIfAbsent("GLOBAL:" + g, k -> {
                        Symbol s = new Symbol();
                        s.name = g;
                        s.kind = "GLOBAL";
                        s.scope = "(global)";
                        s.defLine = -1;
                        return s;
                    });
                    sym.refs.add(tag.startLine + 1);
                }
            }

            // Insert symbols and xref
            try (var psSym = conn.prepareStatement("INSERT INTO symbols(name,kind,scope,def_line,attributes) VALUES(?,?,?,?,?)");
                 var psXref = conn.prepareStatement("INSERT INTO xref(symbol_id,line_num) VALUES(?,?)")) {
                for (Symbol sym : symbols.values()) {
                    psSym.setString(1, sym.name);
                    psSym.setString(2, sym.kind);
                    psSym.setString(3, sym.scope);
                    psSym.setInt(4, sym.defLine);
                    psSym.setString(5, sym.attributes);
                    psSym.executeUpdate();
                    // Get the generated id
                    try (var rs = psSym.getGeneratedKeys()) {
                        if (rs.next()) {
                            int symId = rs.getInt(1);
                            for (int lineNum : sym.refs) {
                                psXref.setInt(1, symId);
                                psXref.setInt(2, lineNum);
                                psXref.executeUpdate();
                            }
                        }
                    }
                }
            }

            // Create indexes for searching
            try (var st = conn.createStatement()) {
                st.execute("CREATE INDEX idx_tags_name ON tags(name)");
                st.execute("CREATE INDEX idx_symbols_name ON symbols(name)");
                st.execute("CREATE INDEX idx_symbols_kind ON symbols(kind)");
                st.execute("CREATE INDEX idx_xref_symbol ON xref(symbol_id)");
                st.execute("CREATE INDEX idx_xref_line ON xref(line_num)");
                st.execute("CREATE INDEX idx_source_line ON source(line_num)");
                st.execute("CREATE INDEX idx_formatted_line ON formatted(line_num)");
            }
        }
    }

    // --- CLI ---
    public static void main(String[] args) throws Exception {
        int indent = 2;
        boolean breakLines = false;
        boolean organize = false;
        boolean xref = false;
        boolean dbMode = false;
        String dbPath = null;
        String file = null;
        for (String a : args) {
            if (a.equals("--break")) breakLines = true;
            else if (a.equals("--organize") || a.equals("--diagram")) organize = true;
            else if (a.equals("--xref") || a.equals("--attributes")) xref = true;
            else if (a.equals("--db")) dbMode = true;
            else if (a.matches("\\d+")) indent = Integer.parseInt(a);
            else if (!a.equals("--indent")) file = a;
        }
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals("--indent")) indent = Integer.parseInt(args[i + 1]);
            if (args[i].equals("--db")) dbPath = args[i + 1];
        }

        String source;
        if (file != null) {
            source = Files.readString(Path.of(file));
        } else {
            source = new String(System.in.readAllBytes());
        }

        MFormatter fmt = new MFormatter(indent, breakLines);
        if (dbPath != null) {
            fmt.writeDatabase(source, dbPath);
            System.out.println("Database written: " + dbPath);
            // Also print xref to stdout
            System.out.print(fmt.xref(source));
        } else if (xref) {
            System.out.print(fmt.xref(source));
        } else if (organize) {
            System.out.print(fmt.organize(source));
        } else {
            System.out.print(fmt.format(source));
        }
    }
}
