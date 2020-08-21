/*
 * Copyright (C) 2009-2020 The Project Lombok Authors.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.javac.handlers;

import static lombok.core.handlers.HandlerUtil.*;
import static lombok.javac.handlers.JavacHandlerUtil.*;
import static lombok.javac.Javac.*;

import java.util.Collection;

import com.sun.tools.javac.util.ListBuffer;
import com.sun.tools.javac.util.Name;
import lombok.ConfigurationKeys;
import lombok.Desensitizer;
import lombok.ToString;
import lombok.core.AnnotationValues;
import lombok.core.configuration.CallSuperType;
import lombok.core.configuration.CheckerFrameworkVersion;
import lombok.core.AST.Kind;
import lombok.core.handlers.InclusionExclusionUtils;
import lombok.core.handlers.InclusionExclusionUtils.Included;
import lombok.javac.JavacAnnotationHandler;
import lombok.javac.JavacNode;
import lombok.javac.JavacTreeMaker;

import org.mangosdk.spi.ProviderFor;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCAnnotation;
import com.sun.tools.javac.tree.JCTree.JCArrayTypeTree;
import com.sun.tools.javac.tree.JCTree.JCBlock;
import com.sun.tools.javac.tree.JCTree.JCClassDecl;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCMethodDecl;
import com.sun.tools.javac.tree.JCTree.JCMethodInvocation;
import com.sun.tools.javac.tree.JCTree.JCModifiers;
import com.sun.tools.javac.tree.JCTree.JCPrimitiveTypeTree;
import com.sun.tools.javac.tree.JCTree.JCStatement;
import com.sun.tools.javac.tree.JCTree.JCTypeParameter;
import com.sun.tools.javac.tree.JCTree.JCVariableDecl;
import com.sun.tools.javac.util.List;

/**
 * Handles the {@code ToString} annotation for javac.
 */
@ProviderFor(JavacAnnotationHandler.class)
public class HandleToString extends JavacAnnotationHandler<ToString> {
	@Override public void handle(AnnotationValues<ToString> annotation, JCAnnotation ast, JavacNode annotationNode) {
		handleFlagUsage(annotationNode, ConfigurationKeys.TO_STRING_FLAG_USAGE, "@ToString");
		
		deleteAnnotationIfNeccessary(annotationNode, ToString.class);
		
		ToString ann = annotation.getInstance();
		java.util.List<Included<JavacNode, ToString.Include>> members = InclusionExclusionUtils.handleToStringMarking(annotationNode.up(), annotation, annotationNode);
		if (members == null) return;
		
		Boolean callSuper = ann.callSuper();
		
		if (!annotation.isExplicit("callSuper")) callSuper = null;
		
		Boolean doNotUseGettersConfiguration = annotationNode.getAst().readConfiguration(ConfigurationKeys.TO_STRING_DO_NOT_USE_GETTERS);
		boolean doNotUseGetters = annotation.isExplicit("doNotUseGetters") || doNotUseGettersConfiguration == null ? ann.doNotUseGetters() : doNotUseGettersConfiguration;
		FieldAccess fieldAccess = doNotUseGetters ? FieldAccess.PREFER_FIELD : FieldAccess.GETTER;
		
		Boolean fieldNamesConfiguration = annotationNode.getAst().readConfiguration(ConfigurationKeys.TO_STRING_INCLUDE_FIELD_NAMES);
		boolean includeNames = annotation.isExplicit("includeFieldNames") || fieldNamesConfiguration == null ? ann.includeFieldNames() : fieldNamesConfiguration;
		
		generateToString(annotationNode.up(), annotationNode, members, includeNames, callSuper, true, fieldAccess);
	}

	public void generateToStringForType(JavacNode typeNode, JavacNode errorNode) {
		if (hasAnnotation(ToString.class, typeNode)) {
			//The annotation will make it happen, so we can skip it.
			return;
		}
		
		boolean includeFieldNames = true;
		try {
			Boolean configuration = typeNode.getAst().readConfiguration(ConfigurationKeys.TO_STRING_INCLUDE_FIELD_NAMES);
			includeFieldNames = configuration != null ? configuration : ((Boolean) ToString.class.getMethod("includeFieldNames").getDefaultValue()).booleanValue();
		} catch (Exception ignore) {}
		
		Boolean doNotUseGettersConfiguration = typeNode.getAst().readConfiguration(ConfigurationKeys.TO_STRING_DO_NOT_USE_GETTERS);
		FieldAccess access = doNotUseGettersConfiguration == null || !doNotUseGettersConfiguration ? FieldAccess.GETTER : FieldAccess.PREFER_FIELD;
		
		java.util.List<Included<JavacNode, ToString.Include>> members = InclusionExclusionUtils.handleToStringMarking(typeNode, null, null);
		generateToString(typeNode, errorNode, members, includeFieldNames, null, false, access);
	}
	
	public void generateToString(JavacNode typeNode, JavacNode source, java.util.List<Included<JavacNode, ToString.Include>> members,
		boolean includeFieldNames, Boolean callSuper, boolean whineIfExists, FieldAccess fieldAccess) {
		generateDesensitizerForType(typeNode, source);
		boolean notAClass = true;
		if (typeNode.get() instanceof JCClassDecl) {
			long flags = ((JCClassDecl) typeNode.get()).mods.flags;
			notAClass = (flags & (Flags.INTERFACE | Flags.ANNOTATION)) != 0;
		}
		
		if (notAClass) {
			source.addError("@ToString is only supported on a class or enum.");
			return;
		}
		
		switch (methodExists("toString", typeNode, 0)) {
		case NOT_EXISTS:
			if (callSuper == null) {
				if (isDirectDescendantOfObject(typeNode)) {
					callSuper = false;
				} else {
					CallSuperType cst = typeNode.getAst().readConfiguration(ConfigurationKeys.TO_STRING_CALL_SUPER);
					if (cst == null) cst = CallSuperType.SKIP;
					switch (cst) {
					default:
					case SKIP:
						callSuper = false;
						break;
					case WARN:
						source.addWarning("Generating toString implementation but without a call to superclass, even though this class does not extend java.lang.Object. If this is intentional, add '@ToString(callSuper=false)' to your type.");
						callSuper = false;
						break;
					case CALL:
						callSuper = true;
						break;
					}
				}
			}
			JCMethodDecl method = createToString(typeNode, members, includeFieldNames, callSuper, fieldAccess, source.get());
			injectMethod(typeNode, method);
			break;
		case EXISTS_BY_LOMBOK:
			break;
		default:
		case EXISTS_BY_USER:
			if (whineIfExists) {
				source.addWarning("Not generating toString(): A method with that name already exists");
			}
			break;
		}
	}
	
	static JCMethodDecl createToString(JavacNode typeNode, Collection<Included<JavacNode, ToString.Include>> members,
		boolean includeNames, boolean callSuper, FieldAccess fieldAccess, JCTree source) {
		
		JavacTreeMaker maker = typeNode.getTreeMaker();
		
		JCAnnotation overrideAnnotation = maker.Annotation(genJavaLangTypeRef(typeNode, "Override"), List.<JCExpression>nil());
		List<JCAnnotation> annsOnMethod = List.of(overrideAnnotation);
		if (getCheckerFrameworkVersion(typeNode).generateSideEffectFree()) annsOnMethod = annsOnMethod.prepend(maker.Annotation(genTypeRef(typeNode, CheckerFrameworkVersion.NAME__SIDE_EFFECT_FREE), List.<JCExpression>nil()));
		JCModifiers mods = maker.Modifiers(Flags.PUBLIC, annsOnMethod);
		JCExpression returnType = genJavaLangTypeRef(typeNode, "String");
		
		boolean first = true;
		
		String typeName = getTypeName(typeNode);
		boolean isEnum = typeNode.isEnumType();
		
		String infix = ", ";
		String suffix = ")";
		String prefix;
		if (callSuper) {
			prefix = "(super=";
		} else if (members.isEmpty()) {
			prefix = isEnum ? "" : "()";
		} else if (includeNames) {
			Included<JavacNode, ToString.Include> firstMember = members.iterator().next();
			String name = firstMember.getInc() == null ? "" : firstMember.getInc().name();
			if (name.isEmpty()) name = firstMember.getNode().getName();
			prefix = "(" + name + "=";
		} else {
			prefix = "(";
		}
		
		JCExpression current;
		if (!isEnum) { 
			current = maker.Literal(typeName + prefix);
		} else {
			current = maker.Binary(CTC_PLUS, maker.Literal(typeName + "."), maker.Apply(List.<JCExpression>nil(),
					maker.Select(maker.Ident(typeNode.toName("this")), typeNode.toName("name")),
					List.<JCExpression>nil()));
			if (!prefix.isEmpty()) current = maker.Binary(CTC_PLUS, current, maker.Literal(prefix));
		}
		
		
		if (callSuper) {
			JCMethodInvocation callToSuper = maker.Apply(List.<JCExpression>nil(),
				maker.Select(maker.Ident(typeNode.toName("super")), typeNode.toName("toString")),
				List.<JCExpression>nil());
			current = maker.Binary(CTC_PLUS, current, callToSuper);
			first = false;
		}
		
		for (Included<JavacNode, ToString.Include> member : members) {
			JCExpression expr;
			
			JCExpression memberAccessor;
			JavacNode memberNode = member.getNode();
			if (memberNode.getKind() == Kind.METHOD) {
				memberAccessor = createMethodAccessor(maker, memberNode);
			} else {
				memberAccessor = createDesensitizeFieldAccessor(maker, memberNode, fieldAccess);
			}
			
			JCExpression memberType = getFieldType(memberNode, fieldAccess);
			
			// The distinction between primitive and object will be useful if we ever add a 'hideNulls' option.
			@SuppressWarnings("unused")
			boolean fieldIsPrimitive = memberType instanceof JCPrimitiveTypeTree;
			boolean fieldIsPrimitiveArray = memberType instanceof JCArrayTypeTree && ((JCArrayTypeTree) memberType).elemtype instanceof JCPrimitiveTypeTree;
			boolean fieldIsObjectArray = !fieldIsPrimitiveArray && memberType instanceof JCArrayTypeTree;
			
			if (fieldIsPrimitiveArray || fieldIsObjectArray) {
				JCExpression tsMethod = chainDots(typeNode, "java", "util", "Arrays", fieldIsObjectArray ? "deepToString" : "toString");
				expr = maker.Apply(List.<JCExpression>nil(), tsMethod, List.<JCExpression>of(memberAccessor));
			} else expr = memberAccessor;
			
			if (first) {
				current = maker.Binary(CTC_PLUS, current, expr);
				first = false;
				continue;
			}
			
			if (includeNames) {
				String n = member.getInc() == null ? "" : member.getInc().name();
				if (n.isEmpty()) n = memberNode.getName();
				current = maker.Binary(CTC_PLUS, current, maker.Literal(infix + n + "="));
			} else {
				current = maker.Binary(CTC_PLUS, current, maker.Literal(infix));
			}
			
			current = maker.Binary(CTC_PLUS, current, expr);
		}
		
		if (!first) current = maker.Binary(CTC_PLUS, current, maker.Literal(suffix));
		
		JCStatement returnStatement = maker.Return(current);
		
		JCBlock body = maker.Block(0, List.of(returnStatement));
		
		JCMethodDecl methodDef = maker.MethodDef(mods, typeNode.toName("toString"), returnType,
			List.<JCTypeParameter>nil(), List.<JCVariableDecl>nil(), List.<JCExpression>nil(), body, null);
		createRelevantNonNullAnnotation(typeNode, methodDef);
		return recursiveSetGeneratedBy(methodDef, source, typeNode.getContext());
	}

	private static void generateDesensitizerForType(JavacNode typeNode, JavacNode source) {
		boolean hasAnnotation = false;
		for (JavacNode field : typeNode.down()) {
			if (hasAnnotation(Desensitizer.class, field)) {
				hasAnnotation = true;
			}
		}
		MemberExistsResult result = methodExists("desensitize", typeNode, 4);
		if (hasAnnotation && MemberExistsResult.NOT_EXISTS == result) {
			createDesensitizeMethod(typeNode, source);
		}
	}

	/**
	 * <pre>
	 *     private String desensitize(Object input, int preLen, int sufLen, String cipher) {
	 *         if (input == null) {
	 *             return null;
	 *         }
	 *         String str = input.toString();
	 *         int len = str.length();
	 *         if (len <= preLen + sufLen) {
	 *             return str;
	 *         }
	 *         StringBuilder sb = new StringBuilder();
	 *         for (int i = 0; i < len; ++i) {
	 *             if (i >= preLen && i < len - sufLen) {
	 *                 sb.append(cipher);
	 *                 continue;
	 *             }
	 *             sb.append(str.charAt(i));
	 *         }
	 *         return sb.toString();
	 *     }
	 * </pre>
	 */
	private static void createDesensitizeMethod(JavacNode typeNode, JavacNode source) {
		JCExpression stringType = genJavaLangTypeRef(typeNode, "String");
		JCExpression objectType = genJavaLangTypeRef(typeNode, "Object");
		JCExpression stringBuilderType = genJavaLangTypeRef(typeNode, "StringBuilder");
		Name inputName = typeNode.toName("input");
		Name preLenName = typeNode.toName("preLen");
		Name sufLenName = typeNode.toName("sufLen");
		Name cipherName = typeNode.toName("cipher");
		Name desensitize = typeNode.toName("desensitize");
		Name sbName = typeNode.toName("sb");
		JavacTreeMaker maker = typeNode.getTreeMaker();
		JCVariableDecl input = maker.VarDef(maker.Modifiers(Flags.PARAMETER), inputName, objectType, null);
		JCVariableDecl preLen = maker.VarDef(maker.Modifiers(Flags.PARAMETER), preLenName, maker.TypeIdent(CTC_INT), null);
		JCVariableDecl sufLen = maker.VarDef(maker.Modifiers(Flags.PARAMETER), sufLenName, maker.TypeIdent(CTC_INT), null);
		JCVariableDecl cipher = maker.VarDef(maker.Modifiers(Flags.PARAMETER), cipherName, stringType, null);
		// Object input, int preLen, int sufLen, String cipher
		List<JCVariableDecl> variableDecls = List.from(new JCVariableDecl[] {input, preLen, sufLen, cipher});
		ListBuffer<JCStatement> statements = new ListBuffer<JCStatement>();

		// if (input == null) return null;
		statements.append(maker.If(maker.Binary(CTC_EQUAL, maker.Ident(inputName), maker.Literal(CTC_BOT, null)), maker.Return(maker.Literal(CTC_BOT, null)), null));

		// String str= input.toString();
		Name strName = typeNode.toName("str");
		Name lenName = typeNode.toName("len");
		JCExpression inputString = maker.Apply(List.<JCExpression>nil(), maker.Select(maker.Ident(inputName), typeNode.toName("toString")), List.<JCExpression>nil());
		statements.append(maker.VarDef(maker.Modifiers(0), strName, stringType, inputString));

		// int len = str.length();
		JCExpression strLength = maker.Apply(List.<JCExpression>nil(), maker.Select(maker.Ident(strName), typeNode.toName("length")), List.<JCExpression>nil());
		statements.append(maker.VarDef(maker.Modifiers(0), lenName, maker.TypeIdent(CTC_INT), strLength));

		// if (len <= preLen + sufLen) return str;
		statements.append(maker.If(maker.Binary(CTC_LESS_OR_EQUAL, maker.Ident(lenName), maker.Binary(CTC_PLUS, maker.Ident(preLenName), maker.Ident(sufLenName))), maker.Return(maker.Ident(strName)), null));

		// for (int i = 0; i < len; ++i)
		Name iName = typeNode.toName("i");
		JCTree.JCNewClass clazz = maker.NewClass(null, List.<JCExpression>nil(), stringBuilderType, List.<JCExpression>nil(), null);
		statements.append(maker.VarDef(maker.Modifiers(0), sbName, stringBuilderType, clazz));
		List<JCStatement> forInit = List.<JCStatement>of(maker.VarDef(maker.Modifiers(0), iName, maker.TypeIdent(CTC_INT), maker.Literal(0)));
		JCExpression forCond = maker.Binary(CTC_LESS_THAN, maker.Ident(iName), maker.Ident(lenName));
		List<JCTree.JCExpressionStatement> forStep = List.of(maker.Exec(maker.Unary(CTC_PREINC, maker.Ident(iName))));
		ListBuffer<JCStatement> forStatement = new ListBuffer<JCStatement>();

		// i >= preLen && i < len - sufLen
		JCExpression ifCond = maker.Binary(
				JavacTreeMaker.TreeTag.treeTag("AND"),
				maker.Binary(CTC_GREATER_OR_EQUAL, maker.Ident(iName), maker.Ident(preLenName)),
				maker.Binary(CTC_LESS_THAN, maker.Ident(iName), maker.Binary(CTC_MINUS, maker.Ident(lenName), maker.Ident(sufLenName)))
		);
		ListBuffer<JCStatement> ifStatements = new ListBuffer<JCStatement>();
		// sb.append(cipher);
		JCMethodInvocation inv = maker.Apply(List.<JCExpression>nil(), maker.Select(maker.Ident(sbName), typeNode.toName("append")), List.<JCExpression>of(maker.Ident(cipherName)));
		ifStatements.append(maker.Exec(inv));
		ifStatements.append(maker.Continue(null));
		forStatement.append(maker.If(ifCond, maker.Block(0, ifStatements.toList()), null));
		//str.chatAt(i)
		JCExpression charAt = maker.Apply(List.<JCExpression>nil(), maker.Select(maker.Ident(strName), typeNode.toName("charAt")), List.<JCExpression>of(maker.Ident(iName)));
		// sb.append(str.chartAt(i))
		JCExpression append = maker.Apply(List.<JCExpression>nil(), maker.Select(maker.Ident(sbName), typeNode.toName("append")), List.<JCExpression>of(charAt));
		forStatement.append(maker.Exec(append));
		statements.append(maker.ForLoop(forInit, forCond, forStep, maker.Block(0, forStatement.toList())));
		JCExpression sbToString = maker.Apply(List.<JCExpression>nil(), maker.Select(maker.Ident(sbName), typeNode.toName("toString")), List.<JCExpression>nil());
		statements.append(maker.Return(sbToString));
		JCMethodDecl methodDec = maker.MethodDef(maker.Modifiers(Flags.PRIVATE), desensitize, stringType, List.<JCTypeParameter>nil(), variableDecls, List.<JCExpression>nil(), maker.Block(0, statements.toList()), null);
		recursiveSetGeneratedBy(methodDec, source.get(), typeNode.getContext());
		injectMethod(typeNode, methodDec);
	}

	private static JCExpression createDesensitizeFieldAccessor(JavacTreeMaker maker, JavacNode field, FieldAccess fieldAccess) {
		JCExpression exp = createFieldAccessor(maker, field, fieldAccess, null);
		JavacNode anno = findAnnotation(Desensitizer.class, field);
		if (anno == null) {
			return exp;
		}
		Desensitizer desensitizer = createAnnotation(Desensitizer.class, (JCAnnotation) anno.get(), field).getInstance();
		JCExpression deMethod = chainDotsString(field.up(), "this.desensitize");
		List<JCExpression> args = List.from(new JCExpression[] {exp, maker.Literal(desensitizer.preLen()), maker.Literal(desensitizer.sufLen()), maker.Literal(desensitizer.cipher())});
		return maker.Apply(List.<JCExpression>nil(), deMethod, args);
	}

	public static String getTypeName(JavacNode typeNode) {
		String typeName = ((JCClassDecl) typeNode.get()).name.toString();
		JavacNode upType = typeNode.up();
		while (upType.getKind() == Kind.TYPE) {
			typeName = ((JCClassDecl) upType.get()).name.toString() + "." + typeName;
			upType = upType.up();
		}
		return typeName;
	}
}
