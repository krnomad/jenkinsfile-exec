package io.localjenkinsfile.runtime

import org.codehaus.groovy.ast.*
import org.codehaus.groovy.ast.expr.*
import org.codehaus.groovy.ast.stmt.*
import org.codehaus.groovy.classgen.GeneratorContext
import org.codehaus.groovy.control.*
import org.codehaus.groovy.control.customizers.CompilationCustomizer
import org.codehaus.groovy.syntax.SyntaxException

/** Validates structure without evaluating any script, then defers environment assignments. */
class DeclarativeCompiler extends CompilationCustomizer {
 final Set<String> steps
 static final Set<String> POST=['always','success','failure','unstable','aborted','cleanup'] as Set
 DeclarativeCompiler(Set<String> steps) { super(CompilePhase.CONVERSION); this.steps=steps }
 void call(SourceUnit source, GeneratorContext context, ClassNode node) {
  if (!node.script) return
  def statements=source.AST.statementBlock.statements
  def pipelines=statements.findAll { callOf(it)?.methodAsString=='pipeline' }
  if (pipelines.size()!=1) { fail(source,node,'Exactly one top-level pipeline { } is required'); return }
  def c=callOf(pipelines[0]); validateBlock(source,closureOf(c),'pipeline')
 }
 static MethodCallExpression callOf(Statement s) {
  s instanceof ExpressionStatement && s.expression instanceof MethodCallExpression ? (MethodCallExpression)s.expression : null
 }
 static List<Expression> args(MethodCallExpression c) { c.arguments instanceof TupleExpression ? c.arguments.expressions : [c.arguments] }
 static ClosureExpression closureOf(MethodCallExpression c) { args(c).find { it instanceof ClosureExpression } as ClosureExpression }
 void validateBlock(SourceUnit src, ClosureExpression closure, String kind) {
  if (closure==null || !(closure.code instanceof BlockStatement)) { fail(src,closure ?: src.AST,'Expected a closure block for '+kind); return }
  Set seen=[]
  closure.code.statements.each { s ->
   def c=callOf(s)
   if (!c || !c.implicitThis || c.methodAsString==null) { fail(src,s,'Only DSL directives/steps are allowed here; use script { } for Groovy control flow'); return }
   String name=c.methodAsString
   def allowed=switch(kind) {
    case 'pipeline' -> ['agent','environment','options','stages','post']
    case 'stages' -> ['stage']
    case 'stage' -> ['agent','environment','options','when','steps','post']
    case 'post' -> POST
    case 'options' -> ['timeout','interactive','aiSession']
    case 'when' -> ['expression','environment','allOf','anyOf','not']
    default -> steps
   }
   if (!allowed.contains(name)) { fail(src,c,"Unsupported ${kind} directive/step '${name}'"); return }
   if (!(kind in ['steps','stages','when']) && !seen.add(name)) fail(src,c,"Duplicate ${name} directive")
   switch(name) {
    case 'environment':
     if (kind=='when') break
     rewriteEnvironment(src,closureOf(c)); break
    case 'stage':
     def a=args(c)
     if (a.size()!=2 || !(a[0] instanceof ConstantExpression) || !(a[0].value instanceof String)) fail(src,c,'stage requires a literal name and closure')
     validateBlock(src,closureOf(c),'stage'); break
    case 'stages': case 'steps': case 'post': case 'options': case 'when':
     validateBlock(src,closureOf(c),name); break
    case 'allOf': case 'anyOf': case 'not': validateBlock(src,closureOf(c),'when'); break
    case 'agent':
     if (args(c).size()!=1 || !(args(c)[0] instanceof VariableExpression) || !(args(c)[0].name in ['any','none'])) fail(src,c,'Only agent any and agent none are supported')
     break
    default:
     if (kind=='post') validateBlock(src,closureOf(c),'steps')
     else if (kind=='steps' && closureOf(c)!=null && name!='script') validateBlock(src,closureOf(c),'steps')
   }
  }
  if (kind=='pipeline' && (!seen.contains('agent') || !seen.contains('stages'))) fail(src,closure,'pipeline requires agent and stages')
  if (kind=='stage' && !seen.contains('steps')) fail(src,closure,'stage requires steps')
  if (kind=='stages' && closure.code.statements.empty) fail(src,closure,'stages must not be empty')
 }
 void rewriteEnvironment(SourceUnit src, ClosureExpression env) {
  if (!env || !(env.code instanceof BlockStatement)) { fail(src,env ?: src.AST,'environment requires assignments'); return }
  Set names=[]
  env.code.statements=env.code.statements.collect { Statement s ->
   def e=s instanceof ExpressionStatement ? s.expression : null
   if (!(e instanceof BinaryExpression) || e.operation.text!='=' || !(e.leftExpression instanceof VariableExpression)) {
    fail(src,s,'environment accepts NAME = expression assignments only'); return s
   }
   String name=e.leftExpression.name
   if (!(name==~ /[A-Za-z_][A-Za-z0-9_]*/) || !names.add(name)) fail(src,e,'Invalid or duplicate environment variable: '+name)
   def deferred=new ClosureExpression(Parameter.EMPTY_ARRAY,new BlockStatement([new ReturnStatement(e.rightExpression)],new VariableScope()))
   deferred.variableScope=new VariableScope()
   def call=new MethodCallExpression(VariableExpression.THIS_EXPRESSION,'__environment',new ArgumentListExpression(new ConstantExpression(name),deferred))
   call.implicitThis=true; call.setSourcePosition(s)
   new ExpressionStatement(call)
  }
 }
 static void fail(SourceUnit src, ASTNode n, String message) { src.addError(new SyntaxException(message,Math.max(n.lineNumber,1),Math.max(n.columnNumber,1))) }
}
