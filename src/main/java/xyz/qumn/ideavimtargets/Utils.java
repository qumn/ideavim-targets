package xyz.qumn.ideavimtargets;

import com.intellij.openapi.editor.Document;

public class Utils {


    public static boolean isSameLine(Document document, int offset1, int offset2) {
        return document.getLineNumber(offset1) == document.getLineNumber(offset2);
    }


    public static int[] getMinNearest(int[] rg1, int[] rg2, int pos, Document document) {
        if (rg1[0] < 0) {
            return rg2;
        }
        if (rg2[0] < 0) {
            return rg1;
        }
        if (isSameLine(document, rg1[0], pos) && !isSameLine(document, rg2[0], pos)){
            return rg1;
        }
        if (!isSameLine(document, rg1[0], pos) && isSameLine(document, rg2[0], pos)){
            return rg2;
        }
        int dest1 = Math.min(Math.abs(pos - rg1[0]), Math.abs(rg1[1] - pos));
        int dest2 = Math.min(Math.abs(pos - rg2[0]), Math.abs(rg2[1] - pos));
        return dest1 < dest2 ? rg1 : rg2;
    }

    public static int LineLeftLimit(Document document, int pos) {
        final int lineNo = document.getLineNumber(pos);
        return document.getLineStartOffset(Math.max(0, lineNo));
    }

    public static int LineRightLimit(Document document, int pos) {
        final int lineNo = document.getLineNumber(pos);
        return document.getLineEndOffset(lineNo);
    }
}
