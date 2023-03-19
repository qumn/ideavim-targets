package xyz.qumn.ideavimtargets;

import com.intellij.openapi.editor.Document;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import java.util.Deque;
import java.util.LinkedList;

public class PairsFinder {

    public static class Pairs {
        // NOTE: brackets must match by the position, and ordered by rank (highest to lowest).
        @NotNull
        private final String openBrackets;
        @NotNull
        private final String closeBrackets;

        static class ParseException extends Exception {
            public ParseException(@NotNull String message) {
                super(message);
            }
        }

        private enum ParseState {
            OPEN,
            COLON,
            CLOSE,
            COMMA,
        }

        /**
         * Constructs @ref BracketPair from a string of bracket pairs with the same syntax
         * as VIM's @c matchpairs option: "(:),{:},[:]"
         *
         * @param bracketPairs comma-separated list of colon-separated bracket pairs.
         * @throws Pairs.ParseException if a syntax error is detected.
         */
        @NotNull
        static Pairs fromBracketPairList(@NotNull final String bracketPairs) throws Pairs.ParseException {
            StringBuilder openBrackets = new StringBuilder();
            StringBuilder closeBrackets = new StringBuilder();
            Pairs.ParseState state = Pairs.ParseState.OPEN;
            for (char ch : bracketPairs.toCharArray()) {
                switch (state) {
                    case OPEN:
                        openBrackets.append(ch);
                        state = Pairs.ParseState.COLON;
                        break;
                    case COLON:
                        if (ch == ':') {
                            state = Pairs.ParseState.CLOSE;
                        } else {
                            throw new Pairs.ParseException("expecting ':', but got '" + ch + "' instead");
                        }
                        break;
                    case CLOSE:
                        final char lastOpenBracket = openBrackets.charAt(openBrackets.length() - 1);
                        if (lastOpenBracket == ch) {
                            throw new Pairs.ParseException("open and close brackets must be different");
                        }
                        closeBrackets.append(ch);
                        state = Pairs.ParseState.COMMA;
                        break;
                    case COMMA:
                        if (ch == ',') {
                            state = Pairs.ParseState.OPEN;
                        } else {
                            throw new Pairs.ParseException("expecting ',', but got '" + ch + "' instead");
                        }
                        break;
                }
            }
            if (state != Pairs.ParseState.COMMA) {
                throw new Pairs.ParseException("list of pairs is incomplete");
            }
            return new Pairs(openBrackets.toString(), closeBrackets.toString());
        }

        Pairs(@NotNull final String openBrackets, @NotNull final String closeBrackets) {
            assert openBrackets.length() == closeBrackets.length();
            this.openBrackets = openBrackets;
            this.closeBrackets = closeBrackets;
        }

        char matchingBracket(char ch) {
            int idx = closeBrackets.indexOf(ch);
            if (idx != -1) {
                return openBrackets.charAt(idx);
            } else {
                assert isOpenBracket(ch);
                idx = openBrackets.indexOf(ch);
                return closeBrackets.charAt(idx);
            }
        }

        boolean isCloseBracket(final int ch) {
            return closeBrackets.indexOf(ch) != -1;
        }

        boolean isOpenBracket(final int ch) {
            return openBrackets.indexOf(ch) != -1;
        }
    }

    public static final Pairs DEFAULT_PAIRS = new Pairs("(<[{", ")>]}");
    @NotNull
    private final CharSequence text;
    @NotNull
    private final Document document;
    @NotNull
    private final Pairs pairs;

    private int leftBound = Integer.MAX_VALUE;
    private int rightBound = Integer.MIN_VALUE;
    private @Nls String error = null;

    private static final int MAX_SEARCH_LINES = 10;
    private static final int MAX_SEARCH_OFFSET = MAX_SEARCH_LINES * 80;

    PairsFinder(@NotNull Document document, @NotNull Pairs pairs) {
        this.text = document.getImmutableCharSequence();
        this.document = document;
        this.pairs = pairs;
    }

    int[] findBoundsAt(int position, int count) throws IllegalStateException {
        if (text.length() == 0) {
            error = "empty document";
            return new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE};
        }
        int[] surroundPairs = findSurroundPairs(position, count);
        int[] nextPairs = findNextPairs(position, count);
        if (isSameLine(surroundPairs[0], position)){
            return surroundPairs;
        }
        if (nextPairs[0] != Integer.MIN_VALUE){
            return nextPairs;
        }
        return surroundPairs;
    }

    int[] findSurroundPairs(int position, int count) throws IllegalStateException {
        leftBound = Math.min(position, leftBound);
        rightBound = Math.max(position, rightBound);
        if (rightBound == leftBound) {
            if (pairs.isCloseBracket(getCharAt(rightBound))) {
                --leftBound;
            } else {
                ++rightBound;
            }
        }
        final int leftLimit = leftLimit(position);
        final int rightLimit = rightLimit(position);
        for (int i = 0; i < count; i++) {
            if (findLeftPair(leftLimit) && findRightPair(rightLimit)) {
                if (pairs.matchingBracket(getCharAt(leftBound)) != getCharAt(rightBound)) {
                    error = "error pair";
                    return new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE};
                }
            }
            if (i + 1 < count) {
                extendTillNext();
            }
        }
        return new int[]{getLeftBound(), getRightBound()};
    }
    int[] findNextPairs(int position, int count){
        int rightLimit = Utils.LineLeftLimit(document,position);
        Deque<Integer> stack = new LinkedList<>();
        int i = position;
        while (i < rightLimit && count > 0) {
            final char ch = getCharAt(i);
            if (pairs.isOpenBracket(ch)) {
                count--;
            }
            i++;
        }
        stack.push(i-1);
        while (i < rightLimit) {
            final char ch = getCharAt(i);
            if (pairs.isOpenBracket(ch)){
                stack.push(i);
            }else if (pairs.isCloseBracket(ch)){
                if (pairs.matchingBracket(getCharAt(stack.peek())) != ch) {
                    break; // a error, e.g. (] {cursor} )
                }
                int leftIdx = stack.pop();
                if (stack.size() == 0){
                    return new int[]{leftIdx, i};
                }
            }
            i++;
        }
        return new int[]{Integer.MIN_VALUE, Integer.MAX_VALUE};
    }

    private boolean findLeftPair(int leftLimit) {
        Deque<Character> stack = new LinkedList<>();
        while (leftBound > leftLimit) {
            final char ch = getCharAt(leftBound);
            if (pairs.isOpenBracket(ch)) {
                if (stack.size() == 0) {
                    return true;
                }
                if (pairs.matchingBracket(stack.peek()) != ch) {
                    return false; // a error, e.g. (] {cursor} )
                } else {
                    stack.pop();
                }
            } else if (pairs.isCloseBracket(ch)) {
                stack.push(ch);
            }
            leftBound--;
        }
        return false;
    }

    private boolean findRightPair(int rightLimit) {
        Deque<Character> stack = new LinkedList<>();
        while (rightBound < rightLimit) {
            final char ch = getCharAt(rightBound);
            if (pairs.isCloseBracket(ch)) {
                if (stack.size() == 0) {
                    return true;
                }
                if (pairs.matchingBracket(stack.peek()) != ch) {
                    return false; // a error, e.g. ( {cursor} [)
                } else {
                    stack.pop();
                }
            } else if (pairs.isOpenBracket(ch)) {
                stack.push(ch);
            }
            rightBound++;
        }
        return false;
    }
    private boolean isSameLine(int offset1, int offset2){
        return document.getLineNumber(offset1) == document.getLineNumber(offset2);
    }
    private char getCharAt(int logicalOffset) {
        assert logicalOffset < text.length();
        return text.charAt(logicalOffset);
    }


    private int leftLimit(final int pos) {
        final int offsetLimit = Math.max(pos - MAX_SEARCH_OFFSET, 0);
        final int lineNo = document.getLineNumber(pos);
        final int lineOffsetLimit = document.getLineStartOffset(Math.max(0, lineNo - MAX_SEARCH_LINES));
        return Math.max(offsetLimit, lineOffsetLimit);
    }

    private int rightLimit(final int pos) {
        final int offsetLimit = Math.min(pos + MAX_SEARCH_OFFSET, text.length());
        final int lineNo = document.getLineNumber(pos);
        final int lineOffsetLimit = document.getLineEndOffset(Math.min(document.getLineCount() - 1, lineNo + MAX_SEARCH_LINES));
        return Math.min(offsetLimit, lineOffsetLimit);
    }

    void extendTillNext() {
        leftBound--;
        rightBound++;
    }

    int getLeftBound() {
        return leftBound;
    }

    int getRightBound() {
        return rightBound;
    }
}
