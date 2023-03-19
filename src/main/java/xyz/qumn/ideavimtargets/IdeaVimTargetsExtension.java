package xyz.qumn.ideavimtargets;

import com.intellij.openapi.editor.Document;
import com.maddyhome.idea.vim.VimPlugin;
import com.maddyhome.idea.vim.api.*;
import com.maddyhome.idea.vim.command.*;
import com.maddyhome.idea.vim.extension.ExtensionHandler;
import com.maddyhome.idea.vim.extension.VimExtension;
import com.maddyhome.idea.vim.handler.TextObjectActionHandler;
import com.maddyhome.idea.vim.common.TextRange;
import com.maddyhome.idea.vim.helper.InlayHelperKt;
import com.maddyhome.idea.vim.helper.MessageHelper;
import com.maddyhome.idea.vim.helper.VimNlsSafe;
import com.maddyhome.idea.vim.listener.SelectionVimListenerSuppressor;
import com.maddyhome.idea.vim.listener.VimListenerSuppressor;
import com.maddyhome.idea.vim.newapi.IjVimCaret;
import com.maddyhome.idea.vim.newapi.IjVimEditor;
import com.maddyhome.idea.vim.vimscript.model.datatypes.VimString;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;


import java.util.EnumSet;

import static com.maddyhome.idea.vim.extension.VimExtensionFacade.putExtensionHandlerMapping;
import static com.maddyhome.idea.vim.extension.VimExtensionFacade.putKeyMappingIfMissing;
import static xyz.qumn.ideavimtargets.PairsFinder.DEFAULT_PAIRS;
import static xyz.qumn.ideavimtargets.Utils.getMinNearest;

public class IdeaVimTargetsExtension implements VimExtension {
    @Override
    public @NotNull String getName() {
        return "targets";
    }

    @Override
    public void init() {
        putExtensionHandlerMapping(MappingMode.XO, VimInjectorKt.getInjector().getParser().parseKeys("<Plug>(InnerPairs)"), getOwner(), new IdeaVimTargetsExtension.TargetsExtensionHandle(true), false);
        putExtensionHandlerMapping(MappingMode.XO, VimInjectorKt.getInjector().getParser().parseKeys("<Plug>(OuterPairs)"), getOwner(), new IdeaVimTargetsExtension.TargetsExtensionHandle(false), false);
        putKeyMappingIfMissing(MappingMode.XO, VimInjectorKt.getInjector().getParser().parseKeys("rb"), getOwner(), VimInjectorKt.getInjector().getParser().parseKeys("<Plug>(InnerPairs)"), true);
        putKeyMappingIfMissing(MappingMode.XO, VimInjectorKt.getInjector().getParser().parseKeys("ab"), getOwner(), VimInjectorKt.getInjector().getParser().parseKeys("<Plug>(OuterPairs)"), true);
    }

    static class TargetsExtensionHandle implements ExtensionHandler {
        final boolean isInner;

        TargetsExtensionHandle(boolean isInner) {
            super();
            this.isInner = isInner;
        }


        static class TargetsTextObjectHandler extends TextObjectActionHandler {
            private final boolean isInner;

            TargetsTextObjectHandler(boolean isInner) {
                this.isInner = isInner;
            }

            @Nullable
            @Override
            public TextRange getRange(@NotNull VimEditor editor,
                                      @NotNull ImmutableVimCaret caret,
                                      @NotNull ExecutionContext context,
                                      int count,
                                      int rawCount,
                                      @Nullable Argument argument) {
                PairsFinder.Pairs bracketPairs = DEFAULT_PAIRS;
                final String bracketPairsVar = bracketPairsVariable();
                if (bracketPairsVar != null) {
                    try {
                        bracketPairs = PairsFinder.Pairs.fromBracketPairList(bracketPairsVar);
                    } catch (PairsFinder.Pairs.ParseException parseException) {
                        @VimNlsSafe String message =
                                MessageHelper.message("argtextobj.invalid.value.of.g.argtextobj.pairs.0", parseException.getMessage());
                        VimPlugin.showMessage(message);
                        VimPlugin.indicateError();
                        return null;
                    }
                }

                int pos = ((IjVimCaret) caret).getCaret().getOffset();
                Document document = ((IjVimEditor) editor).getEditor().getDocument();
                final PairsFinder finder = new PairsFinder(document, bracketPairs);
                final QuoteFinder quoteFinder = new QuoteFinder(document, QuoteFinder.DEFAULT_QUOTES, pos);
                int[] pairsRange = finder.findBoundsAt(pos, count);
                int[] quoteRange = quoteFinder.findBoundsAt(pos, count);

                int[] nearestRg = getMinNearest(quoteRange, pairsRange, pos, document);

                if (nearestRg[0] == Integer.MIN_VALUE) {
                    VimPlugin.showMessage("can not find any pairs or quotes");
                    VimPlugin.indicateError();
                    return null;
                }
                if (isInner) {
                    nearestRg[0]++;
                } else {
                    nearestRg[1]++;
                }
                return new TextRange(nearestRg[0], nearestRg[1]);
            }


            @NotNull
            @Override
            public TextObjectVisualType getVisualType() {
                return TextObjectVisualType.CHARACTER_WISE;
            }
        }

        @Override
        public void execute(@NotNull VimEditor editor, @NotNull ExecutionContext context) {

            IjVimEditor vimEditor = (IjVimEditor) editor;
            @NotNull VimStateMachine vimStateMachine = VimStateMachine.getInstance(vimEditor);
            int count = Math.max(1, vimStateMachine.getCommandBuilder().getCount());

            final TargetsTextObjectHandler textObjectHandler = new TargetsTextObjectHandler(isInner);
            if (!vimStateMachine.isOperatorPending()) {
                editor.nativeCarets().forEach((VimCaret caret) -> {
                    final TextRange range = textObjectHandler.getRange(editor, caret, context, count, 0, null);
                    if (range != null) {
                        try (VimListenerSuppressor.Locked ignored = SelectionVimListenerSuppressor.INSTANCE.lock()) {
                            if (vimStateMachine.getMode() == VimStateMachine.Mode.VISUAL) {
                                com.maddyhome.idea.vim.group.visual.EngineVisualGroupKt.vimSetSelection(caret, range.getStartOffset(), range.getEndOffset() - 1, true);
                            } else {
                                InlayHelperKt.moveToInlayAwareOffset(((IjVimCaret) caret).getCaret(), range.getStartOffset());
                            }
                        }
                    }
                });
            } else {
                vimStateMachine.getCommandBuilder().completeCommandPart(new Argument(new Command(count,
                        textObjectHandler, Command.Type.MOTION, EnumSet.noneOf(CommandFlags.class))));
            }
        }
    }


    @Nullable
    private static String bracketPairsVariable() {
        final Object value = VimPlugin.getVariableService().getGlobalVariableValue("targets_pairs");
        if (value instanceof VimString) {
            VimString vimValue = (VimString) value;
            return vimValue.getValue();
        }
        return null;
    }
}
