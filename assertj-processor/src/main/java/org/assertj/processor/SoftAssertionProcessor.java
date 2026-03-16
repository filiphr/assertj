/*
 * Copyright 2012-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.assertj.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.TypeVariableName;

/**
 * Annotation processor that generates soft assertion entry point interfaces.
 * <p>
 * Scans for concrete Assert classes in {@code org.assertj.core.api} that extend {@code AbstractAssert}
 * and have a single-parameter constructor. Generates:
 * <ul>
 *   <li>{@code GeneratedStandardSoftAssertionsProvider} with {@code assertThat()} methods</li>
 *   <li>{@code GeneratedBDDSoftAssertionsProvider} with {@code then()} methods</li>
 * </ul>
 */
@SupportedAnnotationTypes("*")
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public class SoftAssertionProcessor extends AbstractProcessor {

  private static final String API_PACKAGE = "org.assertj.core.api";
  private static final ClassName SOFT_ASSERTIONS_PROVIDER = ClassName.get(API_PACKAGE, "SoftAssertionsProvider");
  private static final ClassName CHECK_RETURN_VALUE = ClassName.get("org.assertj.core.annotation", "CheckReturnValue");

  private boolean generated = false;

  @Override
  public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
    if (generated) return false;

    processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
        "SoftAssertionProcessor running, root elements: " + roundEnv.getRootElements().size());

    List<EntryPointInfo> entryPoints = new ArrayList<>();

    for (Element element : roundEnv.getRootElements()) {
      if (!(element instanceof TypeElement typeElement)) continue;
      if (typeElement.getKind() != ElementKind.CLASS) continue;
      if (typeElement.getModifiers().contains(Modifier.ABSTRACT)) continue;

      String qualifiedName = typeElement.getQualifiedName().toString();
      if (!qualifiedName.startsWith(API_PACKAGE + ".")) continue;

      String simpleName = typeElement.getSimpleName().toString();
      if (!simpleName.endsWith("Assert")) continue;
      if (simpleName.startsWith("Abstract")) continue;
      if (simpleName.contains("SoftAssertions")) continue;

      // Check if it extends AbstractAssert (directly or transitively)
      if (!extendsAbstractAssert(typeElement)) continue;

      // Find single-parameter constructor
      ExecutableElement constructor = findSingleParamConstructor(typeElement);
      if (constructor == null) continue;

      VariableElement param = constructor.getParameters().get(0);
      TypeName actualType = TypeName.get(param.asType());

      // Skip Assert classes with generic constructor parameters for now.
      // These require complex type variable handling and are kept in the hand-written provider.
      if (actualType instanceof ParameterizedTypeName || containsTypeVariables(actualType)) continue;

      TypeName rawActualType = rawType(actualType);
      ClassName assertClass = ClassName.get(typeElement);

      entryPoints.add(new EntryPointInfo(assertClass, actualType, rawActualType));
    }

    if (!entryPoints.isEmpty()) {
      writeInterface("GeneratedStandardSoftAssertionsProvider", entryPoints, "assertThat");
      writeInterface("GeneratedBDDSoftAssertionsProvider", entryPoints, "then");
      generated = true;
    }

    return false;
  }

  private boolean extendsAbstractAssert(TypeElement typeElement) {
    TypeMirror superType = typeElement.getSuperclass();
    while (superType.getKind() == TypeKind.DECLARED) {
      TypeElement superElement = (TypeElement) ((DeclaredType) superType).asElement();
      String name = superElement.getSimpleName().toString();
      if (name.equals("AbstractAssert")) return true;
      if (name.equals("Object")) return false;
      superType = superElement.getSuperclass();
    }
    return false;
  }

  private ExecutableElement findSingleParamConstructor(TypeElement typeElement) {
    for (ExecutableElement constructor : ElementFilter.constructorsIn(typeElement.getEnclosedElements())) {
      if (constructor.getParameters().size() == 1) {
        return constructor;
      }
    }
    return null;
  }

  private void writeInterface(String interfaceName, List<EntryPointInfo> entryPoints, String methodName) {
    TypeSpec.Builder interfaceBuilder = TypeSpec.interfaceBuilder(interfaceName)
        .addModifiers(Modifier.PUBLIC)
        .addSuperinterface(SOFT_ASSERTIONS_PROVIDER)
        .addAnnotation(CHECK_RETURN_VALUE);

    for (EntryPointInfo ep : entryPoints) {
      MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(methodName)
          .addModifiers(Modifier.PUBLIC, Modifier.DEFAULT)
          .returns(ep.assertClass)
          .addParameter(ep.actualType, "actual")
          .addStatement("return proxy($T.class, $T.class, actual)", ep.assertClass, ep.rawActualType)
          .addJavadoc("Creates a new soft assertion instance of {@link $T}.\n", ep.assertClass)
          .addJavadoc("\n")
          .addJavadoc("@param actual the actual value.\n")
          .addJavadoc("@return the created assertion object.\n");

      interfaceBuilder.addMethod(methodBuilder.build());
    }

    JavaFile javaFile = JavaFile.builder(API_PACKAGE, interfaceBuilder.build())
        .indent("  ")
        .build();

    try {
      javaFile.writeTo(processingEnv.getFiler());
    } catch (IOException e) {
      processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
          "Failed to generate " + interfaceName + ": " + e.getMessage());
    }
  }

  private static boolean containsTypeVariables(TypeName typeName) {
    if (typeName instanceof TypeVariableName) return true;
    if (typeName instanceof ParameterizedTypeName parameterized) {
      return parameterized.typeArguments().stream().anyMatch(SoftAssertionProcessor::containsTypeVariables);
    }
    if (typeName instanceof com.palantir.javapoet.WildcardTypeName wildcard) {
      return wildcard.upperBounds().stream().anyMatch(SoftAssertionProcessor::containsTypeVariables)
          || wildcard.lowerBounds().stream().anyMatch(SoftAssertionProcessor::containsTypeVariables);
    }
    if (typeName instanceof com.palantir.javapoet.ArrayTypeName arrayType) {
      return containsTypeVariables(arrayType.componentType());
    }
    return false;
  }

  private static TypeName rawType(TypeName typeName) {
    if (typeName instanceof com.palantir.javapoet.ParameterizedTypeName parameterized) {
      return parameterized.rawType();
    }
    if (typeName instanceof com.palantir.javapoet.ArrayTypeName) {
      return typeName; // arrays don't have raw types
    }
    return typeName;
  }

  private record EntryPointInfo(ClassName assertClass, TypeName actualType, TypeName rawActualType) {}
}
