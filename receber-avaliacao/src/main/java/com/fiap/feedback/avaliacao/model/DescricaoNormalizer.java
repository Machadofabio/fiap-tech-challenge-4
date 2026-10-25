package com.fiap.feedback.avaliacao.model;

public final class DescricaoNormalizer {
    private DescricaoNormalizer() {
    }

    public static String removerEspacosUnicodeDasExtremidades(String value) {
        int start = 0;
        int end = value.length();

        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isUnicodeWhiteSpace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }

        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!isUnicodeWhiteSpace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }

        return value.substring(start, end);
    }

    private static boolean isUnicodeWhiteSpace(int codePoint) {
        return (codePoint >= 0x0009 && codePoint <= 0x000D)
                || codePoint == 0x0020
                || codePoint == 0x0085
                || codePoint == 0x00A0
                || codePoint == 0x1680
                || (codePoint >= 0x2000 && codePoint <= 0x200A)
                || codePoint == 0x2028
                || codePoint == 0x2029
                || codePoint == 0x202F
                || codePoint == 0x205F
                || codePoint == 0x3000;
    }
}
