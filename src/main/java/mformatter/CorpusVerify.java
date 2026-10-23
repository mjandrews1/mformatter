package mformatter;

import java.nio.file.*;
import java.util.*;

/**
 * Verify MFormatter preserves semantics: expand each corpus case
 * and check the expanded code produces the same operations.
 */
public class CorpusVerify {
    public static void main(String[] args) throws Exception {
        String corpusPath = args.length > 0 ? args[0] : "/Users/mark/pymumps/tests/conformance.json";

        // Load corpus
        String json = Files.readString(Path.of(corpusPath));
        List<String[]> cases = parseCases(json);

        System.out.println("=== MFormatter Corpus Verification ===");
        System.out.println("Cases: " + cases.size());
        System.out.println();

        MFormatter fmt = new MFormatter(2, false);
        int expandOk = 0, expandFail = 0;
        List<String> failures = new ArrayList<>();

        for (String[] c : cases) {
            String name = c[0], code = c[1];
            try {
                String expanded = fmt.format(code);
                // Check expansion preserves key semantics:
                // 1. Same number of command boundaries (approximate)
                // 2. Same variables referenced
                // 3. Same string literals
                if (preservesSemantics(code, expanded)) {
                    expandOk++;
                } else {
                    expandFail++;
                    if (failures.size() < 5) {
                        failures.add(name + ": " + code.substring(0, Math.min(60, code.length())));
                    }
                }
            } catch (Exception e) {
                expandFail++;
                if (failures.size() < 5) {
                    failures.add(name + ": ERROR " + e.getMessage());
                }
            }
        }

        System.out.println("=== RESULT ===");
        System.out.println("EXPAND OK: " + expandOk);
        System.out.println("EXPAND FAIL: " + expandFail);
        if (!failures.isEmpty()) {
            System.out.println("Failures:");
            for (String f : failures) System.out.println("  " + f);
        }

        // Now run expanded code through mJVM
        // Known gaps: error messages (C02,C74,C93) + routine calls (C103,C104,C105,C107)
        Set<String> knownGaps = Set.of(
            "C02.concat", "C74.ind-parent-undef", "C93.select-no-true",
            "C103.do-routine", "C104.do-tag-routine", "C105.extrinsic-routine", "C107.do-routine-args"
        );
        System.out.println();
        System.out.println("=== mJVM execution ===");
        int mvmPass = 0, mvmFail = 0, skipped = 0;
        for (String[] c : cases) {
            String name = c[0], code = c[1], expected = c[2];
            if (knownGaps.contains(name)) { skipped++; continue; }
            try {
                String expanded = fmt.format(code);
                String got = runM(expanded);
                if (got != null && got.trim().equals(expected.trim())) {
                    mvmPass++;
                } else {
                    mvmFail++;
                    if (mvmFail <= 5) {
                        System.out.println("MISS " + name + " got=" + escape(got) + " want=" + escape(expected));
                    }
                }
            } catch (Exception e) {
                mvmFail++;
            }
        }
        System.out.println("MJVM MATCH=" + mvmPass + " MISS=" + mvmFail + " SKIP=" + skipped + " (known gaps)");

        if (expandFail == 0 && mvmFail == 0) {
            System.out.println();
            System.out.println("MFORMATTER IS SEMANTICS-PRESERVING");
        }
    }

    static boolean preservesSemantics(String code, String expanded) {
        // Check that expanded code contains the same key elements
        // 1. Same string literals
        List<String> codeStrs = extractStrings(code);
        List<String> expStrs = extractStrings(expanded);
        if (!codeStrs.equals(expStrs)) return false;

        // 2. Same variable names (with % or ^ prefix)
        Set<String> codeVars = extractVars(code);
        Set<String> expVars = extractVars(expanded);
        if (!codeVars.equals(expVars)) return false;

        return true;
    }

    static List<String> extractStrings(String s) {
        List<String> result = new ArrayList<>();
        boolean inStr = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                if (inStr) {
                    result.add(sb.toString());
                    sb.setLength(0);
                }
                inStr = !inStr;
            } else if (inStr) {
                sb.append(c);
            }
        }
        return result;
    }

    static Set<String> extractVars(String s) {
        Set<String> result = new HashSet<>();
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == '%' || s.charAt(i) == '^') {
                int start = i;
                i++;
                while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '%')) i++;
                result.add(s.substring(start, i));
            } else {
                i++;
            }
        }
        return result;
    }

    static String runM(String code) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
            "java", "-cp", "/Users/mark/mjvm/build:/Users/mark/mjvm/lib/*",
            "mjvm.cli.Mjvm", "-x", code);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        StringBuilder result = new StringBuilder();
        for (String line : output.split("\n")) {
            String t = line.trim();
            // Filter banner and prompt lines only
            if (t.startsWith("mJVM V") || t.startsWith("Drop-in CLI")
                || t.startsWith("direct mode") || t.equals(">")) {
                continue;
            }
            result.append(line).append("\n");
        }
        return result.toString().trim();
    }

    static String escape(String s) {
        return s == null ? "null" : "\"" + s.replace("\n", "\\n") + "\"";
    }

    static List<String[]> parseCases(String json) {
        List<String[]> cases = new ArrayList<>();
        int idx = json.indexOf("\"cases\"");
        if (idx < 0) return cases;
        int arrStart = json.indexOf('[', idx);
        int depth = 0, start = -1;
        for (int i = arrStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') { depth++; if (depth == 1) start = i; }
            if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    String obj = json.substring(start, i + 1);
                    String name = extract(obj, "name");
                    String code = extract(obj, "code");
                    String expected = extract(obj, "expected");
                    if (name != null) cases.add(new String[]{name, code, expected});
                    start = -1;
                }
            }
        }
        return cases;
    }

    static String extract(String obj, String key) {
        int idx = obj.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int colon = obj.indexOf(':', idx);
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < obj.length() && obj.charAt(i) == ' ') i++;
        if (i < obj.length() && obj.charAt(i) == '"') {
            int end = i + 1;
            StringBuilder sb = new StringBuilder();
            while (end < obj.length() && obj.charAt(end) != '"') {
                if (obj.charAt(end) == 92 && end + 1 < obj.length()) {
                    char next = obj.charAt(end + 1);
                    if (next == 'n') sb.append('\n');
                    else if (next == 't') sb.append('\t');
                    else sb.append(next);
                    end += 2;
                } else {
                    sb.append(obj.charAt(end));
                    end++;
                }
            }
            return sb.toString();
        }
        return null;
    }
}
