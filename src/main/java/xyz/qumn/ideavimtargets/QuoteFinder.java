package xyz.qumn.ideavimtargets;

import com.intellij.openapi.editor.Document;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class QuoteFinder {
    private static class Quotes {
        String delimiter;

        boolean isQuote(final int ch) {
            return delimiter.indexOf(ch) != -1;
        }

        Quotes(String delimiter) {
            this.delimiter = delimiter;
        }
    }

    public static final Quotes DEFAULT_QUOTES = new Quotes("'\"`");

    @NotNull
    private final CharSequence text;
    @NotNull
    private final Document document;
    private final Quotes quotes;
    private final int pos;
    // each element contains three item: start, end, quote
    private final List<int[]> lineQuotes = new ArrayList<>();
    private String error;


    QuoteFinder(@NotNull Document document, Quotes quotes, int position) {
        this.text = document.getImmutableCharSequence();
        this.document = document;
        this.quotes = quotes;
        this.pos = position;
    }


    public void buildLineQuotes() {
        int leftLimit = Utils.LineLeftLimit(document, pos);
        int rightLimit = Utils.LineRightLimit(document, pos);
        Deque<Integer> stack = new LinkedList<>();
        boolean isPreEsc = false;
        for (int i = leftLimit; i <= rightLimit; i++) {
            char ch = getCharAt(i);
            if (!isPreEsc && quotes.isQuote(ch)) {
                if (stack.size() > 0 && getCharAt(stack.peek()) == ch) {
                    lineQuotes.add(new int[]{stack.pop(), i, ch});
                } else {
                    stack.push(i);
                }
            } else {
                isPreEsc = ch == '\\';
            }
        }
        lineQuotes.sort(Comparator.comparingInt(a -> a[0]));
    }

    int[] findBoundsAt(int position, int count) throws IllegalStateException {
        if (text.length() == 0) {
            error = "empty document";
            return new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE};
        }
        buildLineQuotes();
        int nearestNQuoteIdx = getNearestNQuote(pos, count);
        int nextNearestNQuote = getNextNearestNQuote(pos, count);
        if (nearestNQuoteIdx == -1 && nextNearestNQuote != -1) {
            return lineQuotes.get(nextNearestNQuote);
        }
        return nearestNQuoteIdx == -1 ? new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE} : lineQuotes.get(nearestNQuoteIdx);
    }

    private int getNearestNQuote(int pos, int count) {
        Queue<Integer> nearestN = new LinkedList<>();
        for (int i = 0; i < lineQuotes.size(); i++) {
            int[] range = lineQuotes.get(i);
            if (range[0] > pos) {
                break;
            }
            if (range[1] >= pos) {
                if (nearestN.size() >= count) {
                    nearestN.remove();
                }
                nearestN.add(i);
            }
        }
        return nearestN.size() == count ? nearestN.remove() : -1;
    }

    private int getNextNearestNQuote(int pos, int count) {
        Queue<Integer> nearestN = new LinkedList<>();
        for (int i = 0; i < lineQuotes.size(); i++) {
            int[] range = lineQuotes.get(i);
            if (range[0] < pos) continue;
            if (nearestN.size() >= count)
                nearestN.remove();
            nearestN.add(i);
        }
        return nearestN.size() == count ? nearestN.remove() : -1;
    }


    private char getCharAt(int logicalOffset) {
        assert logicalOffset < text.length();
        return text.charAt(logicalOffset);
    }
}
