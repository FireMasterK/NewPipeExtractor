package org.schabi.newpipe.extractor.services.youtube;

import org.mozilla.javascript.ast.Assignment;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.ElementGet;
import org.mozilla.javascript.ast.ExpressionStatement;
import org.mozilla.javascript.ast.FunctionCall;
import org.mozilla.javascript.ast.FunctionNode;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.ParenthesizedExpression;
import org.mozilla.javascript.ast.PropertyGet;
import org.mozilla.javascript.ast.StringLiteral;
import org.mozilla.javascript.ast.VariableDeclaration;
import org.mozilla.javascript.ast.VariableInitializer;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.utils.JavaScript;
import org.schabi.newpipe.extractor.utils.Parser;

import javax.annotation.Nonnull;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Utility class to get the signature timestamp of YouTube's base JavaScript player and deobfuscate
 * signature of streaming URLs from HTML5 clients.
 */
final class YoutubeSignatureUtils {

    /**
     * The name of the deobfuscation function which needs to be called inside the deobfuscation
     * code.
     */
    static final String DEOBFUSCATION_FUNCTION_NAME = "deobfuscate";

    private static final String STS_REGEX = "signatureTimestamp[=:](\\d+)";

    private static final String AST_SOLVER_SENTINEL_FIRST_ARGUMENT = "alr";
    private static final String AST_SOLVER_SENTINEL_SECOND_ARGUMENT = "yes";

    private YoutubeSignatureUtils() {
    }

    /**
     * Get the signature timestamp property of YouTube's base JavaScript file.
     *
     * @param javaScriptPlayerCode the complete JavaScript base player code
     * @return the signature timestamp
     * @throws ParsingException if the signature timestamp couldn't be extracted
     */
    @Nonnull
    static String getSignatureTimestamp(@Nonnull final String javaScriptPlayerCode)
            throws ParsingException {
        try {
            return Parser.matchGroup1(STS_REGEX, javaScriptPlayerCode);
        } catch (final ParsingException e) {
            throw new ParsingException(
                    "Could not extract signature timestamp from JavaScript code", e);
        }
    }

    /**
     * Get the signature deobfuscation code of YouTube's base JavaScript file.
     *
     * @param javaScriptPlayerCode the complete JavaScript base player code
     * @return the signature deobfuscation code
     * @throws ParsingException if the signature deobfuscation code couldn't be extracted
     */
    @Nonnull
    static String getDeobfuscationCode(@Nonnull final String javaScriptPlayerCode)
            throws ParsingException {
        try {
            final String astSolverTestUrl =
                "https://youtube.com/watch?v=yt-dlp-wins";
            final AstPlayerExtraction playerExtraction =
                extractAstPlayerStatements(javaScriptPlayerCode);
            final List<AstNode> playerStatements = playerExtraction.playerStatements;
            final Set<String> solverExpressions = extractAstSolverExpressions(
                javaScriptPlayerCode, playerStatements);

            if (solverExpressions.isEmpty()) {
            throw new ParsingException(
                "Could not find AST-based deobfuscation function");
            }

            final StringBuilder code = new StringBuilder(
                buildAstEnvironmentSetup(astSolverTestUrl))
                .append(playerExtraction.setupCode);
            for (final AstNode statement : playerStatements) {
            code.append(getSourceForAstNode(javaScriptPlayerCode, statement))
                .append(';');
            }

            code.append(buildAstSolverCode(solverExpressions, astSolverTestUrl));

            final String deobfuscationCode = code.toString();
            JavaScript.compileOrThrow(deobfuscationCode);
            return deobfuscationCode;
        } catch (final Exception e) {
            throw new ParsingException("Could not parse deobfuscation function", e);
        }
    }

    @Nonnull
    private static AstPlayerExtraction extractAstPlayerStatements(
            @Nonnull final String javaScriptPlayerCode) throws ParsingException {
        final AstRoot astRoot;
        try {
            astRoot = new org.mozilla.javascript.Parser().parse(
                    javaScriptPlayerCode, null, 1);
        } catch (final Exception e) {
            throw new ParsingException("Could not parse JavaScript player AST", e);
        }

        final AstPlayerExtraction extraction =
                unwrapAstPlayerStatements(javaScriptPlayerCode, astRoot);
        final List<AstNode> filteredStatements = new ArrayList<>();
        for (final AstNode statement : extraction.playerStatements) {
            if (shouldKeepAstStatement(statement)) {
                filteredStatements.add(statement);
            }
        }
        return new AstPlayerExtraction(filteredStatements, extraction.setupCode);
    }

    @Nonnull
    private static AstPlayerExtraction unwrapAstPlayerStatements(
            @Nonnull final String javaScriptPlayerCode,
            @Nonnull final AstRoot astRoot) {
        final List<AstNode> rootStatements = getAstChildStatements(astRoot);

        if (!rootStatements.isEmpty()) {
            final FunctionCall wrappedFunctionCall =
                    getWrappedFunctionCall(rootStatements.get(rootStatements.size() - 1));
            if (wrappedFunctionCall != null) {
                final FunctionNode wrappedFunction =
                        getWrappedFunction(wrappedFunctionCall.getTarget());
                final StringBuilder setupCode = new StringBuilder();

                for (int i = 0; i < rootStatements.size() - 1; i++) {
                    setupCode.append(getSourceForAstNode(
                            javaScriptPlayerCode, rootStatements.get(i))).append(';');
                }
                setupCode.append(buildWrappedFunctionBindings(
                        javaScriptPlayerCode, wrappedFunction, wrappedFunctionCall));

                return new AstPlayerExtraction(
                        getAstChildStatements(wrappedFunction.getBody()),
                        setupCode.toString());
            }
        }

        return new AstPlayerExtraction(rootStatements, "");
    }

    private static FunctionCall getWrappedFunctionCall(@Nonnull final AstNode statement) {
        if (!(statement instanceof ExpressionStatement)) {
            return null;
        }

        final AstNode expression = ((ExpressionStatement) statement).getExpression();
        if (!(expression instanceof FunctionCall)) {
            return null;
        }

        return getWrappedFunction(((FunctionCall) expression).getTarget()) == null
                ? null
                : (FunctionCall) expression;
    }

    private static FunctionNode getWrappedFunction(@Nonnull final AstNode callTarget) {
        final AstNode unwrappedTarget = unwrapParenthesizedExpression(callTarget);

        if (unwrappedTarget instanceof FunctionNode) {
            return (FunctionNode) unwrappedTarget;
        }

        if (unwrappedTarget instanceof PropertyGet) {
            final AstNode propertyTarget =
                    unwrapParenthesizedExpression(((PropertyGet) unwrappedTarget).getTarget());
            if (propertyTarget instanceof FunctionNode) {
                return (FunctionNode) propertyTarget;
            }
        }

        return null;
    }

    @Nonnull
    private static AstNode unwrapParenthesizedExpression(@Nonnull final AstNode node) {
        AstNode currentNode = node;
        while (currentNode instanceof ParenthesizedExpression) {
            currentNode = ((ParenthesizedExpression) currentNode).getExpression();
        }
        return currentNode;
    }

    @Nonnull
    private static String buildWrappedFunctionBindings(
            @Nonnull final String javaScriptPlayerCode,
            @Nonnull final FunctionNode wrappedFunction,
            @Nonnull final FunctionCall wrappedFunctionCall) {
        final StringBuilder bindings = new StringBuilder();
        final List<AstNode> parameters = wrappedFunction.getParams();
        final List<AstNode> arguments = wrappedFunctionCall.getArguments();

        for (int i = 0; i < parameters.size(); i++) {
            if (!(parameters.get(i) instanceof Name)) {
                continue;
            }

            bindings.append("var ")
                    .append(((Name) parameters.get(i)).getIdentifier())
                    .append('=');

            if (i < arguments.size()) {
                bindings.append('(')
                        .append(getSourceForAstNode(javaScriptPlayerCode, arguments.get(i)))
                        .append(')');
            } else {
                bindings.append("undefined");
            }

            bindings.append(';');
        }

        return bindings.toString();
    }

    private static boolean shouldKeepAstStatement(@Nonnull final AstNode statement) {
        if (statement instanceof ExpressionStatement) {
            final AstNode expression = ((ExpressionStatement) statement).getExpression();
            return expression instanceof Assignment || expression instanceof StringLiteral;
        }
        return true;
    }

    @Nonnull
    private static Set<String> extractAstSolverExpressions(
            @Nonnull final String javaScriptPlayerCode,
            @Nonnull final List<AstNode> statements) {
        final Set<String> solverExpressions = new LinkedHashSet<>();
        for (final AstNode statement : statements) {
            for (final AstSolverCandidate candidate : getAstSolverCandidates(statement)) {
                if (hasAstSolverSentinel(candidate.functionStatements)) {
                    final String solverExpression =
                            getSourceForAstNode(javaScriptPlayerCode, candidate.expression);
                    solverExpressions.add(solverExpression);
                }
            }
        }
        return solverExpressions;
    }

    @Nonnull
    private static List<AstSolverCandidate> getAstSolverCandidates(
            @Nonnull final AstNode statement) {
        final List<AstSolverCandidate> candidates = new ArrayList<>();

        if (statement instanceof FunctionNode) {
            final FunctionNode functionNode = (FunctionNode) statement;
            addAstSolverCandidate(
                    candidates, functionNode.getFunctionName(), functionNode.getBody());
            return candidates;
        }

        if (statement instanceof ExpressionStatement) {
            final AstNode expression = ((ExpressionStatement) statement).getExpression();
            if (expression instanceof Assignment) {
                final Assignment assignment = (Assignment) expression;
                if (assignment.getRight() instanceof FunctionNode) {
                    addAstSolverCandidate(candidates,
                            assignment.getLeft(),
                            ((FunctionNode) assignment.getRight()).getBody());
                }
            }
            return candidates;
        }

        if (statement instanceof VariableDeclaration) {
            for (final VariableInitializer variable
                    : ((VariableDeclaration) statement).getVariables()) {
                if (variable.getInitializer() instanceof FunctionNode) {
                    addAstSolverCandidate(candidates,
                            variable.getTarget(),
                            ((FunctionNode) variable.getInitializer()).getBody());
                }
            }
        }

        return candidates;
    }

    private static void addAstSolverCandidate(
            @Nonnull final List<AstSolverCandidate> candidates,
            @Nonnull final AstNode expression,
            @Nonnull final AstNode functionBody) {
        if (isAstSolverExpression(expression)) {
            candidates.add(new AstSolverCandidate(
                    expression, getAstChildStatements(functionBody)));
        }
    }

    private static boolean isAstSolverExpression(@Nonnull final AstNode expression) {
        return expression instanceof Name
                || expression instanceof PropertyGet
                || expression instanceof ElementGet;
    }

    private static boolean hasAstSolverSentinel(@Nonnull final List<AstNode> statements) {
        for (final AstNode statement : statements) {
            if (isAstSolverSentinel(statement)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAstSolverSentinel(@Nonnull final AstNode statement) {
        if (!(statement instanceof ExpressionStatement)) {
            return false;
        }

        final AstNode expression = ((ExpressionStatement) statement).getExpression();
        if (!(expression instanceof FunctionCall)) {
            return false;
        }

        final FunctionCall functionCall = (FunctionCall) expression;
        if (!(functionCall.getTarget() instanceof PropertyGet)
                || !(((PropertyGet) functionCall.getTarget()).getTarget() instanceof Name)) {
            return false;
        }

        final List<AstNode> arguments = functionCall.getArguments();
        return arguments.size() == 2
                && isStringLiteral(arguments.get(0), AST_SOLVER_SENTINEL_FIRST_ARGUMENT)
                && isStringLiteral(arguments.get(1), AST_SOLVER_SENTINEL_SECOND_ARGUMENT);
    }

    private static boolean isStringLiteral(@Nonnull final AstNode node,
                                           @Nonnull final String expectedValue) {
        return node instanceof StringLiteral
                && expectedValue.equals(((StringLiteral) node).getValue());
    }

    @Nonnull
    private static List<AstNode> getAstChildStatements(@Nonnull final AstNode parent) {
        final List<AstNode> statements = new ArrayList<>();
        for (final org.mozilla.javascript.Node child : parent) {
            if (child instanceof AstNode) {
                statements.add((AstNode) child);
            }
        }
        return statements;
    }

    @Nonnull
    private static String getSourceForAstNode(@Nonnull final String javaScriptPlayerCode,
                                              @Nonnull final AstNode node) {
        final int start = node.getAbsolutePosition();
        return javaScriptPlayerCode.substring(start, start + node.getLength());
    }

    @Nonnull
    private static String buildAstEnvironmentSetup(@Nonnull final String astSolverTestUrl) {
        return ""
                + "if(typeof globalThis==='undefined'){var globalThis=this;}"
                + "if(typeof globalThis.XMLHttpRequest==='undefined'){"
                + "globalThis.XMLHttpRequest={prototype:{}};}"
                + "globalThis.location={"
                + "hash:'',host:'www.youtube.com',hostname:'www.youtube.com',"
                + "href:'" + astSolverTestUrl + "',"
                + "origin:'https://www.youtube.com',password:'',"
                + "pathname:'/watch',port:'',protocol:'https:',"
                + "search:'?v=',username:''};"
                + "if(typeof globalThis.document==='undefined'){"
                + "globalThis.document=Object.create(null);}"
                + "if(typeof globalThis.navigator==='undefined'){"
                + "globalThis.navigator=Object.create(null);}"
                + "if(typeof globalThis.self==='undefined'){globalThis.self=globalThis;}"
                + "if(typeof globalThis.window==='undefined'){globalThis.window=globalThis;}";
    }

    @Nonnull
    private static String buildAstSolverCode(@Nonnull final Set<String> solverExpressions,
                                             @Nonnull final String astSolverTestUrl) {
        final StringBuilder code = new StringBuilder();

        int index = 0;
        for (final String solverExpression : solverExpressions) {
            code.append("function __newpipeSigSolver")
                    .append(index)
                    .append("(_sig){")
                    .append("var _url=(")
                    .append(solverExpression)
                    .append(")('")
                    .append(astSolverTestUrl)
                    .append("','s',_sig?encodeURIComponent(_sig):undefined);")
                    .append("_url.set('n',undefined);")
                    .append("var _proto=Object.getPrototypeOf(_url)||{};")
                    .append("var _keys=Object.keys(_proto);")
                    .append("try{_keys=_keys.concat(Object.getOwnPropertyNames(_proto));}")
                    .append("catch(_ignored){}")
                    .append("for(var _i=0;_i<_keys.length;_i++){")
                    .append("var _key=_keys[_i];")
                    .append("if(_key!=='constructor'&&_key!=='set'")
                    .append("&&_key!=='get'&&_key!=='clone'){")
                    .append("_url[_key]();break;}}")
                    .append("var _s=_url.get('s');")
                    .append("return _s?decodeURIComponent(_s):'';}");
            index++;
        }

        code.append("function ")
                .append(DEOBFUSCATION_FUNCTION_NAME)
                .append("(a){var _results=[];");

        for (int i = 0; i < solverExpressions.size(); i++) {
            code.append("try{var _result")
                    .append(i)
                    .append("=__newpipeSigSolver")
                    .append(i)
                    .append("(a);")
                    .append("if(_results.indexOf(_result")
                    .append(i)
                    .append(")==-1){")
                    .append("_results.push(_result")
                    .append(i)
                    .append(");}}catch(_error")
                    .append(i)
                    .append("){}");
        }

        code.append("if(_results.length===1){return _results[0];}")
                .append("if(_results.length===0){")
                .append("throw new Error('No AST signature solver succeeded');}")
                .append("throw new Error(")
                .append("'AST signature solvers returned inconsistent results');}");

        return code.toString();
    }

    private static final class AstSolverCandidate {
        private final AstNode expression;
        private final List<AstNode> functionStatements;

        private AstSolverCandidate(@Nonnull final AstNode expression,
                                   @Nonnull final List<AstNode> functionStatements) {
            this.expression = expression;
            this.functionStatements = functionStatements;
        }
    }

    private static final class AstPlayerExtraction {
        private final List<AstNode> playerStatements;
        private final String setupCode;

        private AstPlayerExtraction(@Nonnull final List<AstNode> playerStatements,
                                    @Nonnull final String setupCode) {
            this.playerStatements = playerStatements;
            this.setupCode = setupCode;
        }
    }
}
