// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.DexAnnotation;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexDefinition;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexLibraryClass;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexReference;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DirectMappedDexApplication;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.shaking.Enqueuer.AppInfoWithLiveness;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.MethodSignatureEquivalence;
import com.android.tools.r8.utils.StringDiagnostic;
import com.android.tools.r8.utils.ThreadUtils;
import com.google.common.base.Equivalence.Wrapper;
import com.google.common.collect.Sets;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class RootSetBuilder {

  private final AppInfo appInfo;
  private final DirectMappedDexApplication application;
  private final Collection<ProguardConfigurationRule> rules;
  private final Map<DexDefinition, ProguardKeepRule> noShrinking = new IdentityHashMap<>();
  private final Set<DexDefinition> noOptimization = Sets.newIdentityHashSet();
  private final Set<DexDefinition> noObfuscation = Sets.newIdentityHashSet();
  private final Set<DexDefinition> reasonAsked = Sets.newIdentityHashSet();
  private final Set<DexDefinition> keepPackageName = Sets.newIdentityHashSet();
  private final Set<ProguardConfigurationRule> rulesThatUseExtendsOrImplementsWrong =
      Sets.newIdentityHashSet();
  private final Set<DexDefinition> checkDiscarded = Sets.newIdentityHashSet();
  private final Set<DexMethod> alwaysInline = Sets.newIdentityHashSet();
  private final Set<DexMethod> forceInline = Sets.newIdentityHashSet();
  private final Set<DexMethod> neverInline = Sets.newIdentityHashSet();
  private final Map<DexDefinition, Map<DexDefinition, ProguardKeepRule>> dependentNoShrinking =
      new IdentityHashMap<>();
  private final Map<DexDefinition, ProguardMemberRule> noSideEffects = new IdentityHashMap<>();
  private final Map<DexDefinition, ProguardMemberRule> assumedValues = new IdentityHashMap<>();
  private final Set<DexReference> identifierNameStrings = Sets.newIdentityHashSet();
  private final InternalOptions options;

  private final DexStringCache dexStringCache = new DexStringCache();
  private final Set<ProguardIfRule> ifRules = Sets.newIdentityHashSet();

  public RootSetBuilder(
      AppInfo appInfo,
      DexApplication application,
      List<ProguardConfigurationRule> rules,
      InternalOptions options) {
    this.appInfo = appInfo;
    this.application = application.asDirect();
    this.rules = rules == null ? null : Collections.unmodifiableCollection(rules);
    this.options = options;
  }

  RootSetBuilder(
      AppInfo appInfo,
      Set<ProguardIfRule> ifRules,
      InternalOptions options) {
    this.appInfo = appInfo;
    this.application = appInfo.app.asDirect();
    this.rules = Collections.unmodifiableCollection(ifRules);
    this.options = options;
  }

  private boolean anySuperTypeMatches(
      DexType type,
      Function<DexType, DexClass> definitionFor,
      ProguardTypeMatcher name,
      ProguardTypeMatcher annotation) {
    while (type != null) {
      DexClass clazz = definitionFor.apply(type);
      if (clazz == null) {
        // TODO(herhut): Warn about broken supertype chain?
        return false;
      }
      if (name.matches(clazz.type) && containsAnnotation(annotation, clazz.annotations)) {
        return true;
      }
      type = clazz.superType;
    }
    return false;
  }

  private boolean anyImplementedInterfaceMatches(
      DexClass clazz,
      Function<DexType, DexClass> definitionFor,
      ProguardTypeMatcher className,
      ProguardTypeMatcher annotation) {
    if (clazz == null) {
      return false;
    }
    for (DexType iface : clazz.interfaces.values) {
      DexClass ifaceClass = definitionFor.apply(iface);
      if (ifaceClass == null) {
        // TODO(herhut): Warn about broken supertype chain?
        return false;
      }
      // TODO(herhut): Maybe it would be better to do this breadth first.
      if ((className.matches(iface) && containsAnnotation(annotation, ifaceClass.annotations))
          || anyImplementedInterfaceMatches(ifaceClass, definitionFor, className, annotation)) {
        return true;
      }
    }
    if (clazz.superType == null) {
      return false;
    }
    DexClass superClass = definitionFor.apply(clazz.superType);
    if (superClass == null) {
      // TODO(herhut): Warn about broken supertype chain?
      return false;
    }
    return anyImplementedInterfaceMatches(superClass, definitionFor, className, annotation);
  }

  // Process a class with the keep rule.
  private void process(
      DexClass clazz,
      ProguardConfigurationRule rule,
      ProguardIfRule ifRule) {
    if (rule.getClassType().matches(clazz) == rule.getClassTypeNegated()) {
      return;
    }
    if (!rule.getClassAccessFlags().containsAll(clazz.accessFlags)) {
      return;
    }
    if (!rule.getNegatedClassAccessFlags().containsNone(clazz.accessFlags)) {
      return;
    }
    if (!containsAnnotation(rule.getClassAnnotation(), clazz.annotations)) {
      return;
    }
    // In principle it should make a difference whether the user specified in a class
    // spec that a class either extends or implements another type. However, proguard
    // seems not to care, so users have started to use this inconsistently. We are thus
    // inconsistent, as well, but tell them.
    // TODO(herhut): One day make this do what it says.
    if (rule.hasInheritanceClassName()) {
      boolean extendsExpected =
          anySuperTypeMatches(
              clazz.superType,
              application::definitionFor,
              rule.getInheritanceClassName(),
              rule.getInheritanceAnnotation());
      boolean implementsExpected = false;
      if (!extendsExpected) {
        implementsExpected =
            anyImplementedInterfaceMatches(
                clazz,
                application::definitionFor,
                rule.getInheritanceClassName(),
                rule.getInheritanceAnnotation());
      }
      if (!extendsExpected && !implementsExpected) {
        return;
      }
      // Warn if users got it wrong, but only warn once.
      if (extendsExpected && !rule.getInheritanceIsExtends()) {
        if (rulesThatUseExtendsOrImplementsWrong.add(rule)) {
          options.reporter.warning(
              new StringDiagnostic(
                  "The rule `" + rule + "` uses implements but actually matches extends."));
        }
      } else if (implementsExpected && rule.getInheritanceIsExtends()) {
        if (rulesThatUseExtendsOrImplementsWrong.add(rule)) {
          options.reporter.warning(
              new StringDiagnostic(
                  "The rule `" + rule + "` uses extends but actually matches implements."));
        }
      }
    }

    if (rule.getClassNames().matches(clazz.type)) {
      Collection<ProguardMemberRule> memberKeepRules = rule.getMemberRules();
      if (rule instanceof ProguardKeepRule) {
        switch (((ProguardKeepRule) rule).getType()) {
          case KEEP_CLASS_MEMBERS: {
            // If we're handling -if consequent part, that means precondition already met.
            DexClass precondition = ifRule != null ? null : clazz;
            markMatchingVisibleMethods(clazz, memberKeepRules, rule, precondition);
            markMatchingFields(clazz, memberKeepRules, rule, precondition);
            break;
          }
          case KEEP_CLASSES_WITH_MEMBERS: {
            if (!allRulesSatisfied(memberKeepRules, clazz)) {
              break;
            }
            // fallthrough;
          }
          case KEEP: {
            markClass(clazz, rule);
            markMatchingVisibleMethods(clazz, memberKeepRules, rule, null);
            markMatchingFields(clazz, memberKeepRules, rule, null);
            break;
          }
          case CONDITIONAL:
            assert rule instanceof ProguardIfRule;
            throw new Unreachable("-if rule will be evaluated separately, not here.");
        }
      } else if (rule instanceof ProguardCheckDiscardRule) {
        if (memberKeepRules.isEmpty()) {
          markClass(clazz, rule);
        } else {
          markMatchingFields(clazz, memberKeepRules, rule, clazz);
          markMatchingMethods(clazz, memberKeepRules, rule, clazz);
        }
      } else if (rule instanceof ProguardWhyAreYouKeepingRule
          || rule instanceof ProguardKeepPackageNamesRule) {
        markClass(clazz, rule);
        markMatchingVisibleMethods(clazz, memberKeepRules, rule, null);
        markMatchingFields(clazz, memberKeepRules, rule, null);
      } else if (rule instanceof ProguardAssumeNoSideEffectRule) {
        markMatchingVisibleMethods(clazz, memberKeepRules, rule, null);
        markMatchingFields(clazz, memberKeepRules, rule, null);
      } else if (rule instanceof InlineRule) {
        markMatchingMethods(clazz, memberKeepRules, rule, null);
      } else if (rule instanceof ProguardAssumeValuesRule) {
        markMatchingVisibleMethods(clazz, memberKeepRules, rule, null);
        markMatchingFields(clazz, memberKeepRules, rule, null);
      } else {
        assert rule instanceof ProguardIdentifierNameStringRule;
        markMatchingFields(clazz, memberKeepRules, rule, null);
        markMatchingMethods(clazz, memberKeepRules, rule, null);
      }
    }
  }

  private void runPerRule(
      ExecutorService executorService,
      List<Future<?>> futures,
      ProguardConfigurationRule rule,
      ProguardIfRule ifRule) {
    List<DexType> specifics = rule.getClassNames().asSpecificDexTypes();
    if (specifics != null) {
      // This keep rule only lists specific type matches.
      // This means there is no need to iterate over all classes.
      for (DexType type : specifics) {
        DexClass clazz = application.definitionFor(type);
        // Ignore keep rule iff it does not reference a class in the app.
        if (clazz != null) {
          process(clazz, rule, ifRule);
        }
      }
    } else {
      futures.add(executorService.submit(() -> {
        for (DexProgramClass clazz : application.classes()) {
          process(clazz, rule, ifRule);
        }
        if (rule.applyToLibraryClasses()) {
          for (DexLibraryClass clazz : application.libraryClasses()) {
            process(clazz, rule, ifRule);
          }
        }
      }));
    }
  }

  public RootSet run(ExecutorService executorService) throws ExecutionException {
    application.timing.begin("Build root set...");
    try {
      List<Future<?>> futures = new ArrayList<>();
      // Mark all the things explicitly listed in keep rules.
      if (rules != null) {
        for (ProguardConfigurationRule rule : rules) {
          if (rule instanceof ProguardIfRule) {
            ProguardIfRule ifRule = (ProguardIfRule) rule;
            ifRules.add(ifRule);
          } else {
            runPerRule(executorService, futures, rule, null);
          }
        }
        ThreadUtils.awaitFutures(futures);
      }
    } finally {
      application.timing.end();
    }
    return new RootSet(
        noShrinking,
        noOptimization,
        noObfuscation,
        reasonAsked,
        keepPackageName,
        checkDiscarded,
        alwaysInline,
        forceInline,
        neverInline,
        noSideEffects,
        assumedValues,
        dependentNoShrinking,
        identifierNameStrings,
        ifRules);
  }

  ConsequentRootSet runForIfRules(
      ExecutorService executorService,
      Set<DexType> liveTypes,
      Set<DexEncodedMethod> liveMethods,
      Set<DexEncodedField> liveFields) throws ExecutionException {
    application.timing.begin("Find consequent items for -if rules...");
    Function<DexType, DexClass> definitionForWithLiveTypes = type -> {
      DexClass clazz = appInfo.definitionFor(type);
      if (clazz != null && liveTypes.contains(clazz.type)) {
        return clazz;
      }
      return null;
    };
    try {
      List<Future<?>> futures = new ArrayList<>();
      if (rules != null) {
        for (ProguardConfigurationRule rule : rules) {
          assert rule instanceof ProguardIfRule;
          ProguardIfRule ifRule = (ProguardIfRule) rule;
          // Depending on which types trigger the -if rule, the application of the subsequent
          // -keep rule may vary (due to back references). So, we need to try all pairs of -if rule
          // and live types.
          for (DexType currentLiveType : liveTypes) {
            if (ifRule.hasInheritanceClassName()) {
              if (!satisfyInheritanceRule(currentLiveType, definitionForWithLiveTypes, ifRule)) {
                // Try another live type since the current one doesn't satisfy the inheritance rule.
                continue;
              }
            }
            if (ifRule.getClassNames().matches(currentLiveType)) {
              Collection<ProguardMemberRule> memberKeepRules = ifRule.getMemberRules();
              if (memberKeepRules.isEmpty()) {
                ProguardIfRule materializedRule = ifRule.materialize();
                runPerRule(
                    executorService, futures, materializedRule.subsequentRule, materializedRule);
                // No member rule to satisfy. Move on to the next live type.
                continue;
              }
              Set<DexDefinition> filteredFields = liveFields.stream()
                  .filter(f -> f.field.getHolder() == currentLiveType)
                  .collect(Collectors.toSet());
              Set<DexDefinition> filteredMethods = liveMethods.stream()
                  .filter(m -> m.method.getHolder() == currentLiveType)
                  .collect(Collectors.toSet());
              // If the number of member rules to hold is more than live members, we can't make it.
              if (filteredFields.size() + filteredMethods.size() < memberKeepRules.size()) {
                continue;
              }
              // Depending on which members trigger the -if rule, the application of the subsequent
              // -keep rule may vary (due to back references). So, we need to try literally all
              // combinations of live members.
              // TODO(b/79486261): Some of those are equivalent from the point of view of -if rule.
              Set<Set<DexDefinition>> combinationsOfMembers = Sets.combinations(
                  Sets.union(filteredFields, filteredMethods), memberKeepRules.size());
              for (Set<DexDefinition> combination : combinationsOfMembers) {
                Set<DexEncodedField> fieldsInCombination =
                    DexDefinition.filterDexEncodedField(combination.stream())
                        .collect(Collectors.toSet());
                Set<DexEncodedMethod> methodsInCombination =
                    DexDefinition.filterDexEncodedMethod(combination.stream())
                        .collect(Collectors.toSet());
                // Member rules are combined as AND logic: if found unsatisfied member rule, this
                // combination of live members is not a good fit.
                boolean satisfied = true;
                for (ProguardMemberRule memberRule : memberKeepRules) {
                  if (!ruleSatisfiedByFields(memberRule, fieldsInCombination)
                      && !ruleSatisfiedByMethods(memberRule, methodsInCombination)) {
                    satisfied = false;
                    break;
                  }
                }
                if (satisfied) {
                  ProguardIfRule materializedRule = ifRule.materialize();
                  runPerRule(
                      executorService, futures, materializedRule.subsequentRule, materializedRule);
                }
              }
            }
          }
        }
        ThreadUtils.awaitFutures(futures);
      }
    } finally {
      application.timing.end();
    }
    return new ConsequentRootSet(noShrinking, noOptimization, noObfuscation);
  }

  private void markMatchingVisibleMethods(
      DexClass clazz,
      Collection<ProguardMemberRule> memberKeepRules,
      ProguardConfigurationRule rule,
      DexClass onlyIfClassKept) {
    Set<Wrapper<DexMethod>> methodsMarked = new HashSet<>();
    Arrays.stream(clazz.directMethods()).forEach(method ->
        markMethod(method, memberKeepRules, methodsMarked, rule, onlyIfClassKept));
    while (clazz != null) {
      Arrays.stream(clazz.virtualMethods()).forEach(method ->
          markMethod(method, memberKeepRules, methodsMarked, rule, onlyIfClassKept));
      clazz = clazz.superType == null ? null : application.definitionFor(clazz.superType);
    }
  }

  private void markMatchingMethods(
      DexClass clazz,
      Collection<ProguardMemberRule> memberKeepRules,
      ProguardConfigurationRule rule,
      DexClass onlyIfClassKept) {
    Arrays.stream(clazz.directMethods()).forEach(method ->
        markMethod(method, memberKeepRules, null, rule, onlyIfClassKept));
    Arrays.stream(clazz.virtualMethods()).forEach(method ->
        markMethod(method, memberKeepRules, null, rule, onlyIfClassKept));
  }

  private void markMatchingFields(
      DexClass clazz,
      Collection<ProguardMemberRule> memberKeepRules,
      ProguardConfigurationRule rule,
      DexClass onlyIfClassKept) {
    clazz.forEachField(field -> markField(field, memberKeepRules, rule, onlyIfClassKept));
  }

  // TODO(67934426): Test this code.
  public static void writeSeeds(
      AppInfoWithLiveness appInfo, PrintStream out, Predicate<DexType> include) {
    for (DexReference seed : appInfo.getPinnedItems()) {
      if (seed.isDexType()) {
        if (include.test(seed.asDexType())) {
          out.println(seed.toSourceString());
        }
      } else if (seed.isDexField()) {
        DexField field = seed.asDexField();
        if (include.test(field.clazz)) {
          out.println(
              field.clazz.toSourceString()
                  + ": "
                  + field.type.toSourceString()
                  + " "
                  + field.name.toSourceString());
        }
      } else {
        assert seed.isDexMethod();
        DexMethod method = seed.asDexMethod();
        if (!include.test(method.holder)) {
          continue;
        }
        out.print(method.holder.toSourceString() + ": ");
        DexEncodedMethod encodedMethod = appInfo.definitionFor(method);
        if (encodedMethod.accessFlags.isConstructor()) {
          if (encodedMethod.accessFlags.isStatic()) {
            out.print(Constants.CLASS_INITIALIZER_NAME);
          } else {
            String holderName = method.holder.toSourceString();
            String constrName = holderName.substring(holderName.lastIndexOf('.') + 1);
            out.print(constrName);
          }
        } else {
          out.print(
              method.proto.returnType.toSourceString() + " " + method.name.toSourceString());
        }
        boolean first = true;
        out.print("(");
        for (DexType param : method.proto.parameters.values) {
          if (!first) {
            out.print(",");
          }
          first = false;
          out.print(param.toSourceString());
        }
        out.println(")");
      }
    }
    out.close();
  }

  private boolean satisfyInheritanceRule(
      DexType type,
      Function<DexType, DexClass> definitionFor,
      ProguardConfigurationRule rule) {
    DexClass clazz = definitionFor.apply(type);
    if (clazz == null) {
      return false;
    }
    return
        anySuperTypeMatches(
            clazz.superType,
            definitionFor,
            rule.getInheritanceClassName(),
            rule.getInheritanceAnnotation())
        || anyImplementedInterfaceMatches(
            clazz,
            definitionFor,
            rule.getInheritanceClassName(),
            rule.getInheritanceAnnotation());
  }

  private boolean allRulesSatisfied(Collection<ProguardMemberRule> memberKeepRules,
      DexClass clazz) {
    for (ProguardMemberRule rule : memberKeepRules) {
      if (!ruleSatisfied(rule, clazz)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Checks whether the given rule is satisfied by this clazz, not taking superclasses into
   * account.
   */
  private boolean ruleSatisfied(ProguardMemberRule rule, DexClass clazz) {
    return ruleSatisfiedByMethods(rule, clazz.directMethods())
        || ruleSatisfiedByMethods(rule, clazz.virtualMethods())
        || ruleSatisfiedByFields(rule, clazz.staticFields())
        || ruleSatisfiedByFields(rule, clazz.instanceFields());
  }

  private boolean ruleSatisfiedByMethods(
      ProguardMemberRule rule,
      Iterable<DexEncodedMethod> methods) {
    if (rule.getRuleType().includesMethods()) {
      for (DexEncodedMethod method : methods) {
        if (rule.matches(method, dexStringCache)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean ruleSatisfiedByMethods(ProguardMemberRule rule, DexEncodedMethod[] methods) {
    if (rule.getRuleType().includesMethods()) {
      for (DexEncodedMethod method : methods) {
        if (rule.matches(method, dexStringCache)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean ruleSatisfiedByFields(
      ProguardMemberRule rule,
      Iterable<DexEncodedField> fields) {
    if (rule.getRuleType().includesFields()) {
      for (DexEncodedField field : fields) {
        if (rule.matches(field, dexStringCache)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean ruleSatisfiedByFields(ProguardMemberRule rule, DexEncodedField[] fields) {
    if (rule.getRuleType().includesFields()) {
      for (DexEncodedField field : fields) {
        if (rule.matches(field, dexStringCache)) {
          return true;
        }
      }
    }
    return false;
  }

  static boolean containsAnnotation(ProguardTypeMatcher classAnnotation,
      DexAnnotationSet annotations) {
    if (classAnnotation == null) {
      return true;
    }
    if (annotations.isEmpty()) {
      return false;
    }
    for (DexAnnotation annotation : annotations.annotations) {
      if (classAnnotation.matches(annotation.annotation.type)) {
        return true;
      }
    }
    return false;
  }

  private void markMethod(
      DexEncodedMethod method,
      Collection<ProguardMemberRule> rules,
      Set<Wrapper<DexMethod>> methodsMarked,
      ProguardConfigurationRule context,
      DexDefinition precondition) {
    if ((methodsMarked != null)
        && methodsMarked.contains(MethodSignatureEquivalence.get().wrap(method.method))) {
      return;
    }
    for (ProguardMemberRule rule : rules) {
      if (rule.matches(method, dexStringCache)) {
        if (Log.ENABLED) {
          Log.verbose(getClass(), "Marking method `%s` due to `%s { %s }`.", method, context,
              rule);
        }
        if (methodsMarked != null) {
          methodsMarked.add(MethodSignatureEquivalence.get().wrap(method.method));
        }
        addItemToSets(method, context, rule, precondition);
      }
    }
  }

  private void markField(
      DexEncodedField field,
      Collection<ProguardMemberRule> rules,
      ProguardConfigurationRule context,
      DexDefinition precondition) {
    for (ProguardMemberRule rule : rules) {
      if (rule.matches(field, dexStringCache)) {
        if (Log.ENABLED) {
          Log.verbose(getClass(), "Marking field `%s` due to `%s { %s }`.", field, context,
              rule);
        }
        addItemToSets(field, context, rule, precondition);
      }
    }
  }

  private void markClass(DexClass clazz, ProguardConfigurationRule rule) {
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Marking class `%s` due to `%s`.", clazz.type, rule);
    }
    addItemToSets(clazz, rule, null, null);
  }

  private void includeDescriptor(DexDefinition item, DexType type, ProguardKeepRule context) {
    if (type.isArrayType()) {
      type = type.toBaseType(application.dexItemFactory);
    }
    if (type.isPrimitiveType()) {
      return;
    }
    DexClass definition = appInfo.definitionFor(type);
    if (definition == null || definition.isLibraryClass()) {
      return;
    }
    // Keep the type if the item is also kept.
    dependentNoShrinking.computeIfAbsent(item, x -> new IdentityHashMap<>())
        .put(definition, context);
    // Unconditionally add to no-obfuscation, as that is only checked for surviving items.
    noObfuscation.add(definition);
  }

  private void includeDescriptorClasses(DexDefinition item, ProguardKeepRule context) {
    if (item.isDexEncodedMethod()) {
      DexMethod method = item.asDexEncodedMethod().method;
      includeDescriptor(item, method.proto.returnType, context);
      for (DexType value : method.proto.parameters.values) {
        includeDescriptor(item, value, context);
      }
    } else if (item.isDexEncodedField()) {
      DexField field = item.asDexEncodedField().field;
      includeDescriptor(item, field.type, context);
    } else {
      assert item.isDexClass();
    }
  }

  private synchronized void addItemToSets(
      DexDefinition item,
      ProguardConfigurationRule context,
      ProguardMemberRule rule,
      DexDefinition precondition) {
    if (context instanceof ProguardKeepRule) {
      ProguardKeepRule keepRule = (ProguardKeepRule) context;
      ProguardKeepRuleModifiers modifiers = keepRule.getModifiers();
      if (!modifiers.allowsShrinking) {
        if (precondition != null) {
          dependentNoShrinking.computeIfAbsent(precondition, x -> new IdentityHashMap<>())
              .put(item, keepRule);
        } else {
          noShrinking.put(item, keepRule);
        }
      }
      if (!modifiers.allowsOptimization) {
        noOptimization.add(item);
      }
      if (!modifiers.allowsObfuscation) {
        noObfuscation.add(item);
      }
      if (modifiers.includeDescriptorClasses) {
        includeDescriptorClasses(item, keepRule);
      }
    } else if (context instanceof ProguardAssumeNoSideEffectRule) {
      noSideEffects.put(item, rule);
    } else if (context instanceof ProguardWhyAreYouKeepingRule) {
      reasonAsked.add(item);
    } else if (context instanceof ProguardKeepPackageNamesRule) {
      keepPackageName.add(item);
    } else if (context instanceof ProguardAssumeValuesRule) {
      assumedValues.put(item, rule);
    } else if (context instanceof ProguardCheckDiscardRule) {
      checkDiscarded.add(item);
    } else if (context instanceof InlineRule) {
      switch (((InlineRule) context).getType()) {
        case ALWAYS:
          if (item.isDexEncodedMethod()) {
            alwaysInline.add(item.asDexEncodedMethod().method);
          }
          break;
        case FORCE:
          if (item.isDexEncodedMethod()) {
            forceInline.add(item.asDexEncodedMethod().method);
          }
          break;
        case NEVER:
          if (item.isDexEncodedMethod()) {
            neverInline.add(item.asDexEncodedMethod().method);
          }
          break;
        default:
          throw new Unreachable();
      }
    } else if (context instanceof ProguardIdentifierNameStringRule) {
      if (item.isDexEncodedField()) {
        identifierNameStrings.add(item.asDexEncodedField().field);
      } else if (item.isDexEncodedMethod()) {
        identifierNameStrings.add(item.asDexEncodedMethod().method);
      }
    }
  }

  public static class RootSet {

    public final Map<DexDefinition, ProguardKeepRule> noShrinking;
    public final Set<DexDefinition> noOptimization;
    public final Set<DexDefinition> noObfuscation;
    public final Set<DexDefinition> reasonAsked;
    public final Set<DexDefinition> keepPackageName;
    public final Set<DexDefinition> checkDiscarded;
    public final Set<DexMethod> alwaysInline;
    public final Set<DexMethod> forceInline;
    public final Set<DexMethod> neverInline;
    public final Map<DexDefinition, ProguardMemberRule> noSideEffects;
    public final Map<DexDefinition, ProguardMemberRule> assumedValues;
    private final Map<DexDefinition, Map<DexDefinition, ProguardKeepRule>> dependentNoShrinking;
    public final Set<DexReference> identifierNameStrings;
    public final Set<ProguardIfRule> ifRules;

    private RootSet(
        Map<DexDefinition, ProguardKeepRule> noShrinking,
        Set<DexDefinition> noOptimization,
        Set<DexDefinition> noObfuscation,
        Set<DexDefinition> reasonAsked,
        Set<DexDefinition> keepPackageName,
        Set<DexDefinition> checkDiscarded,
        Set<DexMethod> alwaysInline,
        Set<DexMethod> forceInline,
        Set<DexMethod> neverInline,
        Map<DexDefinition, ProguardMemberRule> noSideEffects,
        Map<DexDefinition, ProguardMemberRule> assumedValues,
        Map<DexDefinition, Map<DexDefinition, ProguardKeepRule>> dependentNoShrinking,
        Set<DexReference> identifierNameStrings,
        Set<ProguardIfRule> ifRules) {
      this.noShrinking = Collections.unmodifiableMap(noShrinking);
      this.noOptimization = noOptimization;
      this.noObfuscation = noObfuscation;
      this.reasonAsked = Collections.unmodifiableSet(reasonAsked);
      this.keepPackageName = Collections.unmodifiableSet(keepPackageName);
      this.checkDiscarded = Collections.unmodifiableSet(checkDiscarded);
      this.alwaysInline = Collections.unmodifiableSet(alwaysInline);
      this.forceInline = Collections.unmodifiableSet(forceInline);
      this.neverInline = Collections.unmodifiableSet(neverInline);
      this.noSideEffects = Collections.unmodifiableMap(noSideEffects);
      this.assumedValues = Collections.unmodifiableMap(assumedValues);
      this.dependentNoShrinking = dependentNoShrinking;
      this.identifierNameStrings = Collections.unmodifiableSet(identifierNameStrings);
      this.ifRules = Collections.unmodifiableSet(ifRules);
    }

    Map<DexDefinition, ProguardKeepRule> getDependentItems(DexDefinition item) {
      return Collections
          .unmodifiableMap(dependentNoShrinking.getOrDefault(item, Collections.emptyMap()));
    }

    private boolean isStaticMember(Entry<DexDefinition, ProguardKeepRule> entry) {
      if (entry.getKey().isDexEncodedMethod()) {
        return (entry.getKey().asDexEncodedMethod()).accessFlags.isStatic();
      }
      if (entry.getKey().isDexEncodedField()) {
        return (entry.getKey().asDexEncodedField()).accessFlags.isStatic();
      }
      return false;
    }

    Map<DexDefinition, ProguardKeepRule> getDependentStaticMembers(DexDefinition item) {
      assert item.isDexClass();
      return getDependentItems(item).entrySet().stream()
          .filter(this::isStaticMember)
          .collect(Collectors.toMap(Entry::getKey, Entry::getValue));
    }

    @Override
    public String toString() {
      StringBuilder builder = new StringBuilder();
      builder.append("RootSet");

      builder.append("\nnoShrinking: " + noShrinking.size());
      builder.append("\nnoOptimization: " + noOptimization.size());
      builder.append("\nnoObfuscation: " + noObfuscation.size());
      builder.append("\nreasonAsked: " + reasonAsked.size());
      builder.append("\nkeepPackageName: " + keepPackageName.size());
      builder.append("\ncheckDiscarded: " + checkDiscarded.size());
      builder.append("\nnoSideEffects: " + noSideEffects.size());
      builder.append("\nassumedValues: " + assumedValues.size());
      builder.append("\ndependentNoShrinking: " + dependentNoShrinking.size());
      builder.append("\nidentifierNameStrings: " + identifierNameStrings.size());
      builder.append("\nifRules: " + ifRules.size());

      builder.append("\n\nNo Shrinking:");
      noShrinking.keySet().stream()
          .sorted(Comparator.comparing(DexDefinition::toSourceString))
          .forEach(a -> builder
              .append("\n").append(a.toSourceString()).append(" ").append(noShrinking.get(a)));
      builder.append("\n");
      return builder.toString();
    }
  }

  // A partial RootSet that becomes live due to the enabled -if rule.
  static class ConsequentRootSet {
    final Map<DexDefinition, ProguardKeepRule> noShrinking;
    final Set<DexDefinition> noOptimization;
    final Set<DexDefinition> noObfuscation;

    private ConsequentRootSet(
        Map<DexDefinition, ProguardKeepRule> noShrinking,
        Set<DexDefinition> noOptimization,
        Set<DexDefinition> noObfuscation) {
      this.noShrinking = Collections.unmodifiableMap(noShrinking);
      this.noOptimization = Collections.unmodifiableSet(noOptimization);
      this.noObfuscation = Collections.unmodifiableSet(noObfuscation);
    }
  }
}
