package com.tyron.nanoj.lang.java.indexing;

/**
 * High-performance lexer that extracts the package name from a Java source file.
 * Skips comments and whitespace. Stops immediately after the package statement.
 */
public class LightweightPackageScanner {

    public static String extractPackage(CharSequence content) {
        int len = content.length();
        int i = 0;
        
        while (i < len) {
            char c = content.charAt(i);

            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            if (c == '/') {
                if (i + 1 >= len) break;
                char next = content.charAt(i + 1);
                if (next == '/') {
                    i = skipLine(content, i);
                    continue;
                } else if (next == '*') {
                    i = skipBlockComment(content, i);
                    continue;
                }
            }

            if (startsWith(content, i, "package")) {
                int start = i + 7; // length of "package"
                // Extract until ';'
                StringBuilder sb = new StringBuilder();
                boolean inWord = false;
                
                for (int k = start; k < len; k++) {
                    char pc = content.charAt(k);
                    if (pc == ';') return sb.toString();
                    if (Character.isWhitespace(pc)) {
                        // Handle "package com . example;" spacing
                        continue; 
                    }
                    sb.append(pc);
                }
                return sb.toString();
            }

            if (Character.isJavaIdentifierStart(c) || c == '@') {
                return ""; // Default package
            }
            
            i++;
        }
        return "";
    }

    private static int skipLine(CharSequence s, int start) {
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '\n') return i + 1;
        }
        return s.length();
    }

    private static int skipBlockComment(CharSequence s, int start) {
        for (int i = start + 2; i < s.length() - 1; i++) {
            if (s.charAt(i) == '*' && s.charAt(i + 1) == '/') return i + 2;
        }
        return s.length();
    }

    private static boolean startsWith(CharSequence content, int start, String prefix) {
        if (start + prefix.length() > content.length()) return false;
        for (int i = 0; i < prefix.length(); i++) {
            if (content.charAt(start + i) != prefix.charAt(i)) return false;
        }
        // Ensure next char is whitespace or end (prevent matching "packageInfo")
        int next = start + prefix.length();
        if (next < content.length()) {
            return !Character.isJavaIdentifierPart(content.charAt(next));
        }
        return true;
    }
}