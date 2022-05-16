package com.oracle.truffle.sl.parser;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.debug.DebuggerTags;
import com.oracle.truffle.api.instrumentation.StandardTags;
import com.oracle.truffle.api.operation.OperationConfig;
import com.oracle.truffle.api.operation.OperationLabel;
import com.oracle.truffle.api.operation.OperationLocal;
import com.oracle.truffle.api.operation.OperationNode;
import com.oracle.truffle.api.operation.OperationNodes;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.sl.SLLanguage;
import com.oracle.truffle.sl.nodes.SLOperationsRootNode;
import com.oracle.truffle.sl.operations.SLOperations;
import com.oracle.truffle.sl.operations.SLOperationsBuilder;
import com.oracle.truffle.sl.parser.SimpleLanguageOperationsParser.ArithmeticContext;
import com.oracle.truffle.sl.parser.SimpleLanguageOperationsParser.BlockContext;
import com.oracle.truffle.sl.parser.SimpleLanguageOperationsParser.Break_statementContext;
import com.oracle.truffle.sl.parser.SimpleLanguageOperationsParser.Continue_statementContext;
import com.oracle.truffle.sl.parser.SimpleLanguageOperationsParser.Debugger_statementContext;
import com.oracle.truffle.sl.parser.SimpleLanguageOperationsParser.ExpressionContext;
import com.oracle.truffle.sl.parser.SimpleLanguageOperationsParser.FunctionContext;
import com.oracle.truffle.sl.parser.SimpleLanguageOperationsParser.If_statementContext;
import com.oracle.truffle.sl.parser.SimpleLanguageOperationsParser.Logic_factorContext;
import com.oracle.truffle.sl.parser.SimpleLanguageOperationsParser.Logic_termContext;
import com.oracle.truffle.sl.parser.SimpleLanguageOperationsParser.MemberAssignContext;
import com.oracle.truffle.sl.parser.SimpleLanguageOperationsParser.MemberCallContext;
import com.oracle.truffle.sl.parser.SimpleLanguageOperationsParser.MemberFieldContext;
import com.oracle.truffle.sl.parser.SimpleLanguageOperationsParser.MemberIndexContext;
import com.oracle.truffle.sl.parser.SimpleLanguageOperationsParser.Member_expressionContext;
import com.oracle.truffle.sl.parser.SimpleLanguageOperationsParser.NameAccessContext;
import com.oracle.truffle.sl.parser.SimpleLanguageOperationsParser.NumericLiteralContext;
import com.oracle.truffle.sl.parser.SimpleLanguageOperationsParser.Return_statementContext;
import com.oracle.truffle.sl.parser.SimpleLanguageOperationsParser.StringLiteralContext;
import com.oracle.truffle.sl.parser.SimpleLanguageOperationsParser.TermContext;
import com.oracle.truffle.sl.parser.SimpleLanguageOperationsParser.While_statementContext;
import com.oracle.truffle.sl.runtime.SLBigNumber;
import com.oracle.truffle.sl.runtime.SLNull;

public class SLOperationsVisitor extends SLBaseVisitor {

    private static final boolean DO_LOG_NODE_CREATION = false;

    public static void parseSL(SLLanguage language, Source source, Map<TruffleString, RootCallTarget> functions) {
        OperationNodes nodes = SLOperationsBuilder.create(OperationConfig.DEFAULT, builder -> {
            SLOperationsVisitor visitor = new SLOperationsVisitor(language, source, builder);
            parseSLImpl(source, visitor);
        });

        for (OperationNode node : nodes.getNodes()) {
            TruffleString name = node.getMetadata(SLOperations.METHOD_NAME);
            SLOperationsRootNode rootNode = new SLOperationsRootNode(language, node);
            RootCallTarget callTarget = rootNode.getCallTarget();
            functions.put(name, callTarget);
        }
    }

    public static Map<TruffleString, RootCallTarget> parseSL(SLLanguage language, Source source) {
        Map<TruffleString, RootCallTarget> roots = new HashMap<>();
        parseSL(language, source, roots);
        return roots;
    }

    private SLOperationsVisitor(SLLanguage language, Source source, SLOperationsBuilder builder) {
        super(language, source);
        this.b = builder;
    }

    private final SLOperationsBuilder b;

    private OperationLabel breakLabel;
    private OperationLabel continueLabel;

    private final ArrayList<OperationLocal> locals = new ArrayList<>();

    @Override
    public Void visit(ParseTree tree) {
        b.beginSourceSection(tree.getSourceInterval().a);
        super.visit(tree);
        b.endSourceSection(tree.getSourceInterval().length());

        return null;
    }

    @Override
    public Void visitFunction(FunctionContext ctx) {
        TruffleString name = asTruffleString(ctx.IDENTIFIER(0).getSymbol(), false);

        b.setMethodName(name);

        b.beginSource(source);
        b.beginTag(StandardTags.RootTag.class);

        int numArguments = enterFunction(ctx).size();

        for (int i = 0; i < numArguments; i++) {
            OperationLocal argLocal = b.createLocal();
            locals.add(argLocal);

            b.beginStoreLocal(argLocal);
            b.emitLoadArgument(i);
            b.endStoreLocal();
        }

        b.beginTag(StandardTags.RootBodyTag.class);

        visit(ctx.body);

        exitFunction();
        locals.clear();

        b.endTag();

        b.beginReturn();
        b.emitConstObject(SLNull.SINGLETON);
        b.endReturn();

        b.endTag();
        b.endSource();

        OperationNode node = b.publish();

        if (DO_LOG_NODE_CREATION) {
            try {
                System.out.println("----------------------------------------------");
                System.out.printf(" Node: %s%n", name);
                System.out.println(node);
                System.out.println("----------------------------------------------");
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    @Override
    public Void visitBlock(BlockContext ctx) {
        b.beginBlock();

        int numLocals = enterBlock(ctx).size();
        for (int i = 0; i < numLocals; i++) {
            locals.add(b.createLocal());
        }

        super.visitBlock(ctx);

        exitBlock();

        b.endBlock();
        return null;
    }

    @Override
    public Void visitBreak_statement(Break_statementContext ctx) {
        if (breakLabel == null) {
            SemErr(ctx.b, "break used outside of loop");
        }

        b.beginTag(StandardTags.StatementTag.class);
        b.emitBranch(breakLabel);
        b.endTag();

        return null;
    }

    @Override
    public Void visitContinue_statement(Continue_statementContext ctx) {
        if (continueLabel == null) {
            SemErr(ctx.c, "continue used outside of loop");
        }

        b.beginTag(StandardTags.StatementTag.class);
        b.emitBranch(continueLabel);
        b.endTag();

        return null;
    }

    @Override
    public Void visitDebugger_statement(Debugger_statementContext ctx) {
        b.beginTag(DebuggerTags.AlwaysHalt.class);
        b.endTag();

        return null;
    }

    @Override
    public Void visitWhile_statement(While_statementContext ctx) {
        OperationLabel oldBreak = breakLabel;
        OperationLabel oldContinue = continueLabel;

        b.beginTag(StandardTags.StatementTag.class);

        breakLabel = b.createLabel();
        continueLabel = b.createLabel();

        b.emitLabel(continueLabel);
        b.beginWhile();

        b.beginSLToBoolean();
        visit(ctx.condition);
        b.endSLToBoolean();

        visit(ctx.body);
        b.endWhile();
        b.emitLabel(breakLabel);

        b.endTag();

        breakLabel = oldBreak;
        continueLabel = oldContinue;

        return null;
    }

    @Override
    public Void visitIf_statement(If_statementContext ctx) {
        b.beginTag(StandardTags.StatementTag.class);

        if (ctx.alt == null) {
            b.beginIfThen();

            b.beginSLToBoolean();
            visit(ctx.condition);
            b.endSLToBoolean();

            visit(ctx.then);
            b.endIfThen();
        } else {
            b.beginIfThenElse();

            b.beginSLToBoolean();
            visit(ctx.condition);
            b.endSLToBoolean();

            visit(ctx.then);

            visit(ctx.alt);
            b.endIfThenElse();
        }

        b.endTag();
        return null;
    }

    @Override
    public Void visitReturn_statement(Return_statementContext ctx) {
        b.beginTag(StandardTags.StatementTag.class);
        b.beginReturn();

        if (ctx.expression() == null) {
            b.emitConstObject(SLNull.SINGLETON);
        } else {
            visit(ctx.expression());
        }

        b.endReturn();
        b.endTag();

        return null;
    }

    /**
     * <pre>
     * a || b
     * </pre>
     *
     * <pre>
     * {
     *  l0 = a;
     *  l0 ? l0 : b;
     * }
     * </pre>
     */
    private void logicalOrBegin(OperationLocal localIdx) {
        b.beginBlock();
        b.beginStoreLocal(localIdx);
    }

    private void logicalOrMiddle(OperationLocal localIdx) {
        b.endStoreLocal();
        b.beginConditional();
        b.beginSLToBoolean();
        b.emitLoadLocal(localIdx);
        b.endSLToBoolean();
        b.emitLoadLocal(localIdx);
    }

    private void logicalOrEnd(@SuppressWarnings("unused") OperationLocal localIdx) {
        b.endConditional();
        b.endBlock();
    }

    @Override
    public Void visitExpression(ExpressionContext ctx) {
        int numTerms = ctx.logic_term().size();

        if (numTerms == 1)
            return visit(ctx.logic_term(0));

        b.beginTag(StandardTags.ExpressionTag.class);

        OperationLocal[] tmpLocals = new OperationLocal[numTerms - 1];
        for (int i = 0; i < numTerms - 1; i++) {
            tmpLocals[i] = b.createLocal();
            logicalOrBegin(tmpLocals[i]);
        }

        for (int i = 0; i < numTerms; i++) {
            visit(ctx.logic_term(i));

            if (i != 0) {
                logicalOrEnd(tmpLocals[i - 1]);
            }

            if (i != numTerms - 1) {
                logicalOrMiddle(tmpLocals[i]);
            }
        }
        b.endTag();

        return null;
    }

    /**
     * <pre>
     * a && b
     * </pre>
     *
     * <pre>
     * {
     *  l0 = a;
     *  l0 ? b : l0;
     * }
     * </pre>
     */
    private void logicalAndBegin(OperationLocal localIdx) {
        b.beginBlock();
        b.beginStoreLocal(localIdx);
    }

    private void logicalAndMiddle(OperationLocal localIdx) {
        b.endStoreLocal();
        b.beginConditional();
        b.beginSLToBoolean();
        b.emitLoadLocal(localIdx);
        b.endSLToBoolean();
    }

    private void logicalAndEnd(OperationLocal localIdx) {
        b.emitLoadLocal(localIdx);
        b.endConditional();
        b.endBlock();
    }

    @Override
    public Void visitLogic_term(Logic_termContext ctx) {
        int numTerms = ctx.logic_factor().size();

        if (numTerms == 1) {
            return visit(ctx.logic_factor(0));
        }

        b.beginTag(StandardTags.ExpressionTag.class);
        b.beginSLUnbox();

        OperationLocal[] tmpLocals = new OperationLocal[numTerms - 1];
        for (int i = 0; i < numTerms - 1; i++) {
            tmpLocals[i] = b.createLocal();
            logicalAndBegin(tmpLocals[i]);
        }

        for (int i = 0; i < numTerms; i++) {
            visit(ctx.logic_factor(i));

            if (i != 0) {
                logicalAndEnd(tmpLocals[i - 1]);
            }

            if (i != numTerms - 1) {
                logicalAndMiddle(tmpLocals[i]);
            }
        }

        b.endSLUnbox();
        b.endTag();

        return null;
    }

    @Override
    public Void visitLogic_factor(Logic_factorContext ctx) {
        if (ctx.arithmetic().size() == 1) {
            return visit(ctx.arithmetic(0));
        }

        b.beginTag(StandardTags.ExpressionTag.class);
        b.beginSLUnbox();

        switch (ctx.OP_COMPARE().getText()) {
            case "<":
                b.beginSLLessThan();
                visit(ctx.arithmetic(0));
                visit(ctx.arithmetic(1));
                b.endSLLessThan();
                break;
            case "<=":
                b.beginSLLessOrEqual();
                visit(ctx.arithmetic(0));
                visit(ctx.arithmetic(1));
                b.endSLLessOrEqual();
                break;
            case ">":
                b.beginSLLogicalNot();
                b.beginSLLessOrEqual();
                visit(ctx.arithmetic(0));
                visit(ctx.arithmetic(1));
                b.endSLLessOrEqual();
                b.endSLLogicalNot();
                break;
            case ">=":
                b.beginSLLogicalNot();
                b.beginSLLessThan();
                visit(ctx.arithmetic(0));
                visit(ctx.arithmetic(1));
                b.endSLLessThan();
                b.endSLLogicalNot();
                break;
            case "==":
                b.beginSLEqual();
                visit(ctx.arithmetic(0));
                visit(ctx.arithmetic(1));
                b.endSLEqual();
                break;
            case "!=":
                b.beginSLLogicalNot();
                b.beginSLEqual();
                visit(ctx.arithmetic(0));
                visit(ctx.arithmetic(1));
                b.endSLEqual();
                b.endSLLogicalNot();
                break;
        }

        b.endSLUnbox();
        b.endTag();

        return null;
    }

    @Override
    public Void visitArithmetic(ArithmeticContext ctx) {

        if (!ctx.OP_ADD().isEmpty()) {
            b.beginTag(StandardTags.ExpressionTag.class);
            b.beginSLUnbox();
        }

        for (int i = ctx.OP_ADD().size() - 1; i >= 0; i--) {
            switch (ctx.OP_ADD(i).getText()) {
                case "+":
                    b.beginSLAdd();
                    break;
                case "-":
                    b.beginSLSub();
                    break;
            }
        }

        visit(ctx.term(0));

        for (int i = 0; i < ctx.OP_ADD().size(); i++) {
            visit(ctx.term(i + 1));

            switch (ctx.OP_ADD(i).getText()) {
                case "+":
                    b.endSLAdd();
                    break;
                case "-":
                    b.endSLSub();
                    break;
            }
        }

        if (!ctx.OP_ADD().isEmpty()) {
            b.endSLUnbox();
            b.endTag();
        }

        return null;
    }

    @Override
    public Void visitTerm(TermContext ctx) {
        if (!ctx.OP_MUL().isEmpty()) {
            b.beginTag(StandardTags.ExpressionTag.class);
            b.beginSLUnbox();
        }
        for (int i = ctx.OP_MUL().size() - 1; i >= 0; i--) {
            switch (ctx.OP_MUL(i).getText()) {
                case "*":
                    b.beginSLMul();
                    break;
                case "/":
                    b.beginSLDiv();
                    break;
            }
        }

        b.beginSLUnbox();
        visit(ctx.factor(0));
        b.endSLUnbox();

        for (int i = 0; i < ctx.OP_MUL().size(); i++) {
            b.beginSLUnbox();
            visit(ctx.factor(i + 1));
            b.endSLUnbox();

            switch (ctx.OP_MUL(i).getText()) {
                case "*":
                    b.endSLMul();
                    break;
                case "/":
                    b.endSLDiv();
                    break;
            }
        }

        if (!ctx.OP_MUL().isEmpty()) {
            b.endSLUnbox();
            b.endTag();
        }

        return null;
    }

    @Override
    public Void visitNameAccess(NameAccessContext ctx) {
        buildMemberExpressionRead(ctx.IDENTIFIER().getSymbol(), ctx.member_expression(), ctx.member_expression().size() - 1);
        return null;
    }

    private void buildMemberExpressionRead(Token ident, List<Member_expressionContext> members, int idx) {
        if (idx == -1) {
            int localIdx = getNameIndex(ident);
            if (localIdx != -1) {
                b.emitLoadLocal(locals.get(localIdx));
            } else {
                b.beginSLFunctionLiteral();
                b.emitConstObject(asTruffleString(ident, false));
                b.endSLFunctionLiteral();
            }
            return;
        }

        Member_expressionContext last = members.get(idx);

        if (last instanceof MemberCallContext) {
            MemberCallContext lastCtx = (MemberCallContext) last;
            b.beginTag(StandardTags.ExpressionTag.class);
            b.beginTag(StandardTags.CallTag.class);
            b.beginSLInvoke();

            buildMemberExpressionRead(ident, members, idx - 1);

            for (ExpressionContext arg : lastCtx.expression()) {
                visit(arg);
            }

            b.endSLInvoke();
            b.endTag();
            b.endTag();
        } else if (last instanceof MemberAssignContext) {
            MemberAssignContext lastCtx = (MemberAssignContext) last;

            buildMemberExpressionWriteBefore(ident, members, idx - 1, lastCtx.expression().start);
            visit(lastCtx.expression());
            buildMemberExpressionWriteAfter(ident, members, idx - 1);
        } else if (last instanceof MemberFieldContext) {
            MemberFieldContext lastCtx = (MemberFieldContext) last;

            b.beginTag(StandardTags.ExpressionTag.class);
            b.beginSLReadProperty();
            buildMemberExpressionRead(ident, members, idx - 1);
            b.emitConstObject(asTruffleString(lastCtx.IDENTIFIER().getSymbol(), false));
            b.endSLReadProperty();
            b.endTag();
        } else {
            MemberIndexContext lastCtx = (MemberIndexContext) last;

            b.beginTag(StandardTags.ExpressionTag.class);
            b.beginSLReadProperty();
            buildMemberExpressionRead(ident, members, idx - 1);
            visit(lastCtx.expression());
            b.endSLReadProperty();
            b.endTag();
        }
    }

    /**
     * <pre>
     * x = a;
     *
     * {
     *  x = a;
     *  x
     * }
     * </pre>
     */

    private final Stack<Integer> writeLocalsStack = new Stack<>();

    private void buildMemberExpressionWriteBefore(Token ident, List<Member_expressionContext> members, int idx, Token errorToken) {
        if (idx == -1) {
            int localIdx = getNameIndex(ident);
            assert localIdx != -1;
            writeLocalsStack.push(localIdx);

            b.beginBlock();
            b.beginStoreLocal(locals.get(localIdx));
            return;
        }

        Member_expressionContext last = members.get(idx);

        if (last instanceof MemberCallContext) {
            SemErr(errorToken, "invalid assignment target");
        } else if (last instanceof MemberAssignContext) {
            SemErr(errorToken, "invalid assignment target");
        } else if (last instanceof MemberFieldContext) {
            MemberFieldContext lastCtx = (MemberFieldContext) last;

            b.beginTag(StandardTags.ExpressionTag.class);
            b.beginSLWriteProperty();
            buildMemberExpressionRead(ident, members, idx - 1);
            b.emitConstObject(asTruffleString(lastCtx.IDENTIFIER().getSymbol(), false));
        } else {
            MemberIndexContext lastCtx = (MemberIndexContext) last;

            b.beginTag(StandardTags.ExpressionTag.class);
            b.beginSLWriteProperty();
            buildMemberExpressionRead(ident, members, idx - 1);
            visit(lastCtx.expression());
        }
    }

    @SuppressWarnings("unused")
    private void buildMemberExpressionWriteAfter(Token ident, List<Member_expressionContext> members, int idx) {
        if (idx == -1) {
            int localIdx = writeLocalsStack.pop();
            b.endStoreLocal();
            b.emitLoadLocal(locals.get(localIdx));
            b.endBlock();
            return;
        }

        b.endSLWriteProperty();
        b.endTag();
    }

    @Override
    public Void visitStringLiteral(StringLiteralContext ctx) {
        b.emitConstObject(asTruffleString(ctx.STRING_LITERAL().getSymbol(), true));
        return null;
    }

    @Override
    public Void visitNumericLiteral(NumericLiteralContext ctx) {
        Object value;
        try {
            value = Long.parseLong(ctx.NUMERIC_LITERAL().getText());
        } catch (NumberFormatException ex) {
            value = new SLBigNumber(new BigInteger(ctx.NUMERIC_LITERAL().getText()));
        }
        b.emitConstObject(value);
        return null;
    }

}
