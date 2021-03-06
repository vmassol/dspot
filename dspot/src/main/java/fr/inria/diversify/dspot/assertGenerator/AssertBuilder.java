package fr.inria.diversify.dspot.assertGenerator;

import fr.inria.diversify.utils.AmplificationHelper;
import fr.inria.diversify.utils.TypeUtils;
import spoon.reflect.code.*;
import spoon.reflect.factory.Factory;
import spoon.reflect.reference.CtExecutableReference;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by Benjamin DANGLOT
 * benjamin.danglot@inria.fr
 * on 3/17/17
 */
public class AssertBuilder {

	private static String junitAssertClassName = "org.junit.Assert";

	static List<CtStatement> buildAssert(Factory factory, Set<String> notDeterministValues, Map<String, Object> observations) {
		return observations.keySet().stream()
				.filter(key -> !notDeterministValues.contains(key))
				/*.filter(key -> !(observations.get(key) instanceof Float ||
						observations.get(key) instanceof Double))*/ // TODO why this predicate has been introduced?
				.collect(ArrayList<CtStatement>::new,
						(expressions, key) -> {
							Object value = observations.get(key);
							final CtVariableAccess variableRead = factory.createVariableRead(
									factory.createLocalVariableReference().setSimpleName(key),
									false
							);
							if (value == null) {
								expressions.add(buildInvocation(factory, "assertNull",
										Collections.singletonList(variableRead))
								);
								variableRead.setType(factory.Type().NULL_TYPE);
							} else {
								if (value instanceof Boolean) {
									expressions.add(
											buildInvocation(factory,
													(Boolean) value ? "assertTrue" : "assertFalse",
													Collections.singletonList(variableRead)
											)
									);
								} else if (TypeUtils.isArray(value)) {//TODO
									expressions.add(buildAssertForArray(factory, key, value));
								} else if (TypeUtils.isPrimitiveCollection(value)) {
									Collection valueCollection = (Collection) value;
									if (valueCollection.isEmpty()) {
										final CtInvocation<?> isEmpty = factory.createInvocation(variableRead,
												factory.Type().get(Collection.class).getMethodsByName("isEmpty").get(0).getReference()
										);
										expressions.add(buildInvocation(factory, "assertTrue",
												Collections.singletonList(isEmpty))
										);
									} else {
										expressions.addAll(buildSnippetAssertCollection(factory, key, (Collection) value));
									}
								} else if (TypeUtils.isPrimitiveMap(value)) {//TODO
									expressions.add(buildSnippetAssertMap(factory, key, (Map) value));
								} else {
									addTypeCastIfNeeded(variableRead, value);
									expressions.add(buildInvocation(factory, "assertEquals",
											Arrays.asList(printPrimitiveString(factory, value),
													variableRead)));
								}
								variableRead.setType(factory.Type().createReference(value.getClass()));
							}
						},
						ArrayList<CtStatement>::addAll);
	}

	private static void addTypeCastIfNeeded(CtVariableAccess<?> variableRead, Object value) {
		if (value instanceof Short) {
			variableRead.addTypeCast(variableRead.getFactory().Type().shortPrimitiveType());
		} else if (value instanceof Integer) {
			variableRead.addTypeCast(variableRead.getFactory().Type().integerPrimitiveType());
		} else if (value instanceof Long) {
			variableRead.addTypeCast(variableRead.getFactory().Type().longPrimitiveType());
		} else if (value instanceof Byte) {
			variableRead.addTypeCast(variableRead.getFactory().Type().bytePrimitiveType());
		} else if (value instanceof Float) {
			variableRead.addTypeCast(variableRead.getFactory().Type().floatPrimitiveType());
		} else if (value instanceof Double) {
			variableRead.addTypeCast(variableRead.getFactory().Type().doublePrimitiveType());
		} else if (value instanceof Character) {
			variableRead.addTypeCast(variableRead.getFactory().Type().characterPrimitiveType());
		}
	}

	private static CtInvocation buildInvocation(Factory factory, String methodName, List<CtExpression> arguments) {
		final CtInvocation invocation = factory.createInvocation();
		final CtExecutableReference<?> executableReference = factory.Core().createExecutableReference();
		executableReference.setStatic(true);
		executableReference.setSimpleName(methodName);
		executableReference.setDeclaringType(factory.createCtTypeReference(org.junit.Assert.class));
		invocation.setExecutable(executableReference);
		invocation.setArguments(arguments); // TODO
		invocation.setType(factory.Type().voidPrimitiveType());
		invocation.setTarget(factory.createTypeAccess(factory.createCtTypeReference(org.junit.Assert.class)));
		return invocation;
	}

	private static CtStatement buildAssertForArray(Factory factory, String expression, Object array) {
		String type = array.getClass().getCanonicalName();
		String arrayLocalVar1 = "array_" + Math.abs(AmplificationHelper.getRandom().nextInt());
		String arrayLocalVar2 = "array_" + Math.abs(AmplificationHelper.getRandom().nextInt());


		String forLoop = "\tfor(int ii = 0; ii <" + arrayLocalVar1 + ".length; ii++) {\n\t\t"
				+ junitAssertClassName + ".assertEquals(" + arrayLocalVar1 + "[ii], " + arrayLocalVar2 + "[ii]);\n\t}";

		return factory.createCodeSnippetStatement(type + " " + arrayLocalVar1 + " = " + primitiveArrayToString(array) + ";\n\t"
				+ type + " " + arrayLocalVar2 + " = " + "(" + type + ")" + expression + ";\n"
				+ forLoop);
	}

	@SuppressWarnings("unchecked")
	private static List<CtStatement> buildSnippetAssertCollection(Factory factory, String expression, Collection value) {
		final CtVariableAccess variableRead = factory.createVariableRead(
				factory.createLocalVariableReference().setSimpleName(expression),
				false
		);
		final CtExecutableReference contains = factory.Type().get(Collection.class).getMethodsByName("contains").get(0).getReference();
		return (List<CtStatement>) value.stream().map(factory::createLiteral)
				.map(o ->
						buildInvocation(factory, "assertTrue",
								Collections.singletonList(factory.createInvocation(variableRead,
										contains, (CtLiteral) o
										)
								)
						)
				)
				.collect(Collectors.toList());
	}

	private static CtStatement buildSnippetAssertMap(Factory factory, String expression, Map value) {
		Random r = new Random();
		String type = value.getClass().getCanonicalName();
		String localVar = "map_" + Math.abs(r.nextInt());
		String newCollection = type + " " + localVar + " = new " + type + "<Object, Object>();";

		Set<Map.Entry> set = value.entrySet();
		for (Map.Entry v : set) {
			newCollection += "\n\t" + localVar + ".put(" + printPrimitiveString(factory, v.getKey())
					+ ", " + printPrimitiveString(factory, v.getValue()) + ");\n";
		}
		newCollection += "\t" + junitAssertClassName + ".assertEquals(" + localVar + ", " + expression + ");";

		return factory.createCodeSnippetStatement(newCollection);
	}

	private static CtExpression printPrimitiveString(Factory factory, Object value) {
		if (value == null || value instanceof String ||
				value instanceof Short ||
				value.getClass() == short.class ||
				value instanceof Double ||
				value.getClass() == double.class ||
				value instanceof Float ||
				value.getClass() == float.class ||
				value instanceof Long ||
				value.getClass() == long.class ||
				value instanceof Character ||
				value.getClass() == char.class ||
				value instanceof Byte ||
				value.getClass() == byte.class ||
				value instanceof Integer ||
				value.getClass() == int.class) {
			return factory.createLiteral(value);
		} else {
			return factory.createCodeSnippetExpression(value.toString());
		}
	}

	private static String primitiveArrayToString(Object array) {
		String type = array.getClass().getCanonicalName();

		String tmp;
		if (type.equals("int[]")) {
			tmp = Arrays.toString((int[]) array);
			return "new int[]{" + tmp.substring(1, tmp.length() - 1) + "}";
		}
		if (type.equals("short[]")) {
			tmp = Arrays.toString((short[]) array);
			return "new short[]{" + tmp.substring(1, tmp.length() - 1) + "}";
		}
		if (type.equals("byte[]")) {
			tmp = Arrays.toString((byte[]) array);
			return "new byte[]{" + tmp.substring(1, tmp.length() - 1) + "}";
		}
		if (type.equals("long[]")) {
			tmp = Arrays.toString((long[]) array);
			return "new long[]{" + tmp.substring(1, tmp.length() - 1) + "}";
		}
		if (type.equals("float[]")) {
			tmp = Arrays.toString((float[]) array);
			return "new float[]{" + tmp.substring(1, tmp.length() - 1) + "}";
		}
		if (type.equals("double[]")) {
			tmp = Arrays.toString((double[]) array);
			return "new double[]{" + tmp.substring(1, tmp.length() - 1) + "}";
		}
		if (type.equals("boolean[]")) {
			tmp = Arrays.toString((boolean[]) array);
			return "new boolean[]{" + tmp.substring(1, tmp.length() - 1) + "}";
		}
		if (type.equals("char[]")) {
			char[] arrayChar = (char[]) array;

			if (arrayChar.length == 0) {
				return "new char[]{}";
			}
			if (arrayChar.length == 1) {
				return "new char[]{\'" + arrayChar[0] + "\'}";
			} else {
				String ret = "new char[]{\'" + arrayChar[0];
				for (int i = 1; i < arrayChar.length - 1; i++) {
					ret += "\',\'" + arrayChar[i];
				}
				return ret + "\'}";
			}
		}
		return null;
	}

}
