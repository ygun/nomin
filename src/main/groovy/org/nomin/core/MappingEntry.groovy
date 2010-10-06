package org.nomin.core

import org.apache.commons.beanutils.ConvertUtils
import org.nomin.Mapping
import org.nomin.util.*
import org.nomin.core.preprocessing.*
import static java.text.MessageFormat.*

/**
 * Contains mapping pair from side a and side b.
 * @author Dmitry Dobrynin
 * Created 08.04.2010 18:38:13
 */
@SuppressWarnings(["GroovyVariableNotAssigned", "GroovyAssignabilityCheck"])
class MappingEntry {
  Mapping mapping
  Map<String, Object> side = [a: [:], b: [:]]
  Introspector introspector
  private MappingCase mappingCase

  /** Checks whether path elements for both sides aren't empty.  */
  boolean completed() { side.a.pathElem && side.b.pathElem }

  /** Defines left or right conversion.  */
  void conversion(conversion) {
    side.a.conversion = conversion.to_b
    side.b.conversion = conversion.to_a
  }

  /** Defines hints for a and/or b side.  */
  void hint(hints) {
    if (hints.a) side.a.hints = hints.a
    if (hints.b) side.b.hints = hints.b
  }

  /**
   * Defines left or right path element. If a path element is specified with 'empty' key it will be stored in firstly
   * available side.
   */
  def pathElem(pathElem) {
    if (pathElem.a) side.a.pathElem = pathElem.a
    else if (pathElem.b) side.b.pathElem = pathElem.b
    else side.a.pathElem ? (side.b.pathElem = pathElem.empty) : (side.a.pathElem = pathElem.empty) // set empty pathElem
  }

  /** Forces the mapper to use a particular mapping case.  */
  void mappingCase(mappingCase) { this.mappingCase = mappingCase }

  MappingRule parse() {
    def a = buildRuleElem(TypeInfo.typeInfo(mapping.sideA), side.a.hints?.iterator(), side.a.pathElem)
    def b = buildRuleElem(TypeInfo.typeInfo(mapping.sideB), side.b.hints?.iterator(), side.b.pathElem)
    def (lastA, lastB) = [findLast(a), findLast(b)]
    validate lastA, lastB
    new MappingRule(a, b, analyzeElem(lastA, lastB, side.a.conversion), allowed(lastB),
            analyzeElem(lastB, lastA, side.b.conversion), allowed(lastA))
  }

  protected void validate(lastA, lastB) {
    if (RootRuleElem.isInstance(lastA) && RootRuleElem.isInstance(lastB))
      throw new NominException(format("{0}: Recursive mapping rule a = b causes infinite loop!", mapping.mappingName))
    if ((lastA.typeInfo.container ^ lastB.typeInfo.container) && PropRuleElem.isInstance(lastA) && PropRuleElem.isInstance(lastB))
      throw new NominException(format("{0}: Mapping rule {1} is invalid because there is a collection/array on the first side and a single value on another!", mapping.mappingName, this))
  }

  /** Finds the last element in the chain.  */
  protected RuleElem findLast(RuleElem elem) { elem.next ? findLast(elem.next) : elem }

  protected Preprocessing analyzeElem(RuleElem source, RuleElem target, Closure conversion) {
    if (conversion) return new ConversionPreprocessing(conversion)
    if (target.typeInfo.dynamic || source.typeInfo.undefined)
      return new DynamicPreprocessing(target.typeInfo, mapping.mapper, mappingCase)
    if (RootRuleElem.isInstance(target)) return new AppliedMapperPreprocessing(mapping.mapper, mappingCase)
    def (sourceType, targetType) = [source.typeInfo.determineType(), target.typeInfo.determineType()]
    if (targetType.isAssignableFrom(sourceType)) return null
    if (ConvertUtils.lookup(sourceType, targetType)) return new ConvertUtilsPreprocessing(target.typeInfo.determineType())
    new MapperPreprocessing(target.typeInfo.determineType(), mapping.mapper, mappingCase)
  }

  protected boolean allowed(RuleElem elem) {
    [PropRuleElem, CollectionRuleElem, ArrayRuleElem, ArraySeqRuleElem, SeqRuleElem, RootRuleElem].contains(elem.class)
  }

  protected RuleElem buildRuleElem(TypeInfo ti, Iterator<TypeInfo> hints, PathElem elem, RuleElem prev = null) {
    RuleElem current = processElem(elem, ti, prev, hints)
    if (prev) prev.next = current
    if (elem.nextPathElem) buildRuleElem(current.typeInfo, hints, elem.nextPathElem, current)
    current
  }

  protected RuleElem applyHint(RuleElem elem, Iterator<TypeInfo> hints) {
    if (hints?.hasNext()) {
      def nextHint = hints.next()
      if (nextHint) elem.typeInfo.merge nextHint
    }
    elem
  }

  protected RootRuleElem processElem(RootPathElem root, TypeInfo typeInfo, RuleElem prev, Iterator<TypeInfo> hints) {
    new RootRuleElem(typeInfo)
  }

  protected RuleElem processElem(PropPathElem elem, TypeInfo ti, RuleElem prev, Iterator<TypeInfo> hints) {
    PropertyAccessor property = introspector.property(elem.prop, ti.type)
    if (!property)
      throw new NominException(format("{0}: Mapping rule {1} is invalid because of missing property {2}.{3}!",
              mapping.mappingName, this, ti.type.simpleName, elem.prop))
    if (property.typeInfo.collection) {
      applyHint(new CollectionRuleElem(property.typeInfo, property), hints)
    } else if (property.typeInfo.array) {
      applyHint(new ArrayRuleElem(property.typeInfo, property), hints)
    } else {
      applyHint(new PropRuleElem(property.typeInfo, property), hints)
    }
  }

  protected RuleElem processElem(SeqPathElem elem, TypeInfo ti, RuleElem prev, Iterator<TypeInfo> hints) {
    if (ti.container) {
      applyHint(prev instanceof ArrayRuleElem ?
        new ArraySeqRuleElem(ti.parameters ? ti.parameters[0] : TypeInfo.typeInfo(Object), elem.index, prev) :
        new SeqRuleElem(ti.parameters ? ti.parameters[0] : TypeInfo.typeInfo(Object), elem.index), hints)
    } else throw new NominException(format("{0}: Mapping rule {1} is invalid because property {2} isn''t indexable!",
            mapping.mappingName, this, prev));
  }

  protected RuleElem processElem(MethodPathElem elem, TypeInfo ti, RuleElem prev, Iterator<TypeInfo> hints) {
    MethodInvocation invocation = introspector.invocation(elem.methodName, ti.type, * elem.params)
    applyHint(new MethodRuleElem(invocation.typeInfo, invocation), hints)
  }

  protected RuleElem processElem(ExprPathElem elem, TypeInfo ti, RuleElem prev, Iterator<TypeInfo> hints) {
    applyHint(Closure.isInstance(elem.expr) ? new ClosureRuleElem(elem.expr) : new ValueRuleElem(elem.expr), hints)
  }

  String toString() {
    if (side.a.pathElem instanceof RootPathElem) "${printElem('a', side.a.pathElem)} = ${printElem('b', side.b.pathElem)}"
    else "${printElem('b', side.b.pathElem)} = ${printElem('a', side.a.pathElem)}"
  }

  protected String printElem(side, RootPathElem elem) {
    if (elem.nextPathElem) "${side}.${printElem(side, elem.nextPathElem)}"; else side
  }

  protected String printElem(side, PropPathElem elem) {
    if (elem.nextPathElem) {
      if (elem.nextPathElem.class == SeqPathElem) "${elem.prop}${printElem(side, elem.nextPathElem)}"; else "${elem.prop}.${printElem(side, elem.nextPathElem)}"
    } else elem.prop
  }

  protected String printElem(side, SeqPathElem elem) {
    if (elem.nextPathElem) {
      if (elem.nextPathElem.class == SeqPathElem) "[${elem.index}]${printElem(side, elem.nextPathElem)}"; else "[${elem.index}].${printElem(side, elem.nextPathElem)}"
    } else "[${elem.index}]"
  }

  protected String printElem(side, MethodPathElem elem) {
    if (elem.nextPathElem) {
      if (elem.nextPathElem.class == SeqPathElem) "${elem.methodName}(${elem.params})${printElem(side, elem.nextPathElem)}"
      else "${elem.methodName}(${elem.params}).${printElem(side, elem.nextPathElem)}"
    } else "${elem.methodName}(${elem.params})"
  }

  protected String printElem(side, ExprPathElem elem) {
    elem.expr instanceof Closure ? "{ expression }" : elem.expr
  }
}