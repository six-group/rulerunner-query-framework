package com.six.iam.util;

import java.lang.reflect.Array;
import java.util.*;
import java.util.Map;
import java.util.regex.Pattern;

public class CerberusLogic
{
	/**
	 * Treat the given object as Iterable using either cast, Map.entrySet(), Collections.singletonList() or Collections.emptyList()
	 */
	@SuppressWarnings("unchecked")
	private static Iterable<Object> asIterable(Object value)
	{
		//@formatter:off
		@SuppressWarnings("NestedConditionalExpression")
		var collection = (Iterable<Object>) (
				(value instanceof Iterable) ? value :
				(value instanceof Map) ? ((Map<Object, Object>) value).entrySet() :
				(value != null) ? Collections.singletonList(value) :
				Collections.emptyList());
		//@formatter:on
		return collection;
	}

	/**
	 * Objects submitted to CerberusLogic Rule evaluation must implement
	 * this interface.
	 */
	public interface IAccessor
	{
		/**
		 * Return a proxy for the provided object
		 */
		<T> IAccessor derive(T payload);

		/**
		 * Return the result of evaluating the given expression
		 */
		<V> V access(String expression);
	}

	/**
	 * Selectors represent the checks CerberusLogic Rules perform on the objects
	 * given to them. This is the abstract base class of all of them.
	 */
	public static abstract class Selector
	{
		protected final boolean isInverted;

		protected Selector(boolean isInverted)
		{
			this.isInverted = isInverted;
		}

		abstract boolean matches(IAccessor target);

		/**
		 * Construct a {@link Selector} or {@link Reference}
		 * @param name name of selector to construct or "ref" to construct a reference
		 * @param args constructor arguments
		 */
		public static Object construct(String name, Object... args)
		{
			return switch (name)
			{
				case "eq" -> eq((String) args[0], slice(Object.class, args, 1));
				case "ne" -> ne((String) args[0], slice(Object.class, args, 1));
				case "between" -> between((String) args[0], args[1], args[2]);
				case "outside" -> outside((String) args[0], args[1], args[2]);
				case "ge" -> ge((String) args[0], args[1]);
				case "gt" -> gt((String) args[0], args[1]);
				case "le" -> le((String) args[0], args[1]);
				case "lt" -> lt((String) args[0], args[1]);
				case "in" -> in((String) args[0], (String) args[1]);
				case "notin" -> notin((String) args[0], (String) args[1]);
				case "like" -> like((String) args[0], slice(Object.class, args, 1));
				case "unlike" -> unlike((String) args[0], slice(Object.class, args, 1));
				case "any" -> any((String) args[0], slice(Selector.class, args, 1));
				case "none" -> none((String) args[0], slice(Selector.class, args, 1));
				case "all" -> all((String) args[0], slice(Selector.class, args, 1));
				case "notall" -> notall((String) args[0], slice(Selector.class, args, 1));
				case "mt" -> mt((String) args[0], args[1], slice(Selector.class, args, 2));
				case "me" -> me((String) args[0], args[1], slice(Selector.class, args, 2));
				case "ft" -> ft((String) args[0], args[1], slice(Selector.class, args, 2));
				case "fe" -> fe((String) args[0], args[1], slice(Selector.class, args, 2));
				case "num" -> num((String) args[0], args[1], args[2], slice(Selector.class, args, 3));
				case "and" -> and(slice(Selector.class, args, 0));
				case "nand" -> nand(slice(Selector.class, args, 0));
				case "not" -> not((Selector) args[0]);
				case "or" -> or(slice(Selector.class, args, 0));
				case "nor" -> nor(slice(Selector.class, args, 0));
				case "ref" -> ref((String) args[0]);
				default -> throw new RuntimeException("Invalid selector: " + name);
			};
		}

		public static Selector eq(String expression, Object... args)
		{
			return new EqualsSelector(expression, args, false);
		}

		public static Selector ne(String expression, Object... args)
		{
			return new EqualsSelector(expression, args, true);
		}

		public static Selector between(String expression, Object lower, Object upper)
		{
			return new RangeSelector(expression, lower, upper, false);
		}

		public static Selector outside(String expression, Object lower, Object upper)
		{
			return new RangeSelector(expression, lower, upper, true);
		}

		public static Selector ge(String expression, Object value)
		{
			return new RangeSelector(expression, value, null, false);
		}

		public static Selector gt(String expression, Object value)
		{
			return new RangeSelector(expression, null, value, true);
		}

		public static Selector le(String expression, Object value)
		{
			return new RangeSelector(expression, null, value, false);
		}

		public static Selector lt(String expression, Object value)
		{
			return new RangeSelector(expression, value, null, true);
		}

		public static Selector in(String expression, String collectionExpression)
		{
			return new EqualsSelector(expression, collectionExpression, false);
		}

		public static Selector notin(String expression, String collectionExpression)
		{
			return new EqualsSelector(expression, collectionExpression, true);
		}

		public static Selector like(String expression, Object... args)
		{
			return new LikeSelector(expression, args, false);
		}

		public static Selector unlike(String expression, Object... args)
		{
			return new LikeSelector(expression, args, true);
		}

		public static Selector any(String expression, Selector... selectors)
		{
			return new AnyAllSelector(expression, selectors, true, false);
		}

		public static Selector none(String expression, Selector... selectors)
		{
			return new AnyAllSelector(expression, selectors, true, true);
		}

		public static Selector all(String expression, Selector... selectors)
		{
			return new AnyAllSelector(expression, selectors, false, false);
		}

		public static Selector notall(String expression, Selector... selectors)
		{
			return new AnyAllSelector(expression, selectors, false, true);
		}

		public static Selector mt(String expression, Object value, Selector... selectors)
		{
			return new CountingSelector(expression, null, value, selectors, true);
		}

		public static Selector me(String expression, Object value, Selector... selectors)
		{
			return new CountingSelector(expression, value, null, selectors, false);
		}

		public static Selector ft(String expression, Object value, Selector... selectors)
		{
			return new CountingSelector(expression, value, null, selectors, true);
		}

		public static Selector fe(String expression, Object value, Selector... selectors)
		{
			return new CountingSelector(expression, null, value, selectors, false);
		}

		public static Selector num(String expression, Object lower, Object upper, Selector... selectors)
		{
			return new CountingSelector(expression, lower, upper, selectors, false);
		}

		public static Selector and(Selector... selectors)
		{
			return new AndOrSelector(selectors, true, false);
		}

		public static Selector nand(Selector... selectors)
		{
			return new AndOrSelector(selectors, true, true);
		}

		public static Selector not(Selector selector)
		{
			return new AndOrSelector(new Selector[]{ selector }, true, true);
		}

		public static Selector or(Selector... selectors)
		{
			return new AndOrSelector(selectors, false, false);
		}

		public static Selector nor(Selector... selectors)
		{
			return new AndOrSelector(selectors, false, true);
		}

		public static Reference ref(String expression)
		{
			return new Reference(expression);
		}

		/**
		 * Return the elements from the <tt>source</tt> array starting at index
		 * <tt>start</tt> as an array of <tt>objectClass</tt> objects. Will throw
		 * a ClassCastException if an object is not of the requested type.
		 */
		@SuppressWarnings("unchecked")
		private static <T> T[] slice(Class<T> objectClass, Object[] source, int start)
		{
			var result = (T[]) Array.newInstance(objectClass, source.length - start);
			for (var index = 0; index < source.length - start; ++index)
			{
				result[index] = (T) source[index + start];
			}
			return result;
		}
	}

	/**
	 * Attribute selectors check an attribute retrieved from the object. Note that
	 * the term "attribute" used here is an abstraction since attribute expressions
	 * are opaque to CerberusLogic and are effectively abstract specifications
	 * that can mean anything that the application program chooses to implement in the
	 * {@link IAccessor} interface. This way, an "attribute" is simply something that
	 * can be retrieved or computed from an object. CerberusLogic does not care
	 * about what computations are done exactly to evaluate the attribute expression
	 * and will deal only with the result.
	 */
	public static abstract class AttributeSelector extends Selector
	{
		protected final String expression;

		protected AttributeSelector(String expression, boolean isInverted)
		{
			super(isInverted);
			this.expression = expression;
		}
	}

	/**
	 * Collection selectors check the elements of collections against a selector.
	 * Obviously, the attribute expression must evaluate to a collection for them.
	 */
	public static abstract class CollectionSelector extends AttributeSelector
	{
		protected final Selector selector;

		protected CollectionSelector(String expression, Selector selector, boolean isInverted)
		{
			super(expression, isInverted);
			this.selector = selector;
		}
	}

	/**
	 * Composite selectors combine the results of other selectors. They do not
	 * examine object attributes on their own.
	 */
	public static abstract class CompositeSelector extends Selector
	{
		protected final Selector[] selectors;

		protected CompositeSelector(Selector[] selectors, boolean isInverted)
		{
			super(isInverted);
			this.selectors = selectors;
		}
	}

	/**
	 * The Equals selector tests an attribute for (un)equality to one or more
	 * values or to all elements of a collection attribute. Variants:
	 * <dl>
	 *     <dt><tt>eq("expression", value...)</tt></dt>
	 *     <dd>Checks if the result of evaluating the attribute expression is equal to one of the provided values.</dd>
	 *     <dt><tt>ne("expression", value...)</tt></dt>
	 *     <dd>Checks if the result of evaluating the attribute expression is not equal to any of the provided values.</dd>
	 *     <dt><tt>in("expression", "collectionExpression")</tt></dt>
	 *     <dd>Checks if the result of evaluating the attribute expression is equal to one of the collection elements.</dd>
	 *     <dt><tt>notin("expression", "collectionExpression")</tt></dt>
	 *     <dd>Checks if the result of evaluating the attribute expression is not equal to any of the collection elements.</dd>
	 * </dl>
	 */
	public static class EqualsSelector extends AttributeSelector
	{
		private final List<Object> values;
		private final String collectionExpression;

		EqualsSelector(String expression, Object[] values, boolean isInverted)
		{
			super(expression, isInverted);
			this.values = Arrays.asList(values);
			collectionExpression = null;
		}

		EqualsSelector(String expression, String collectionExpression, boolean isInverted)
		{
			super(expression, isInverted);
			values = null;
			this.collectionExpression = collectionExpression;
		}

		@Override
		boolean matches(IAccessor target)
		{
			var testee = target.access(expression);
			var values = (collectionExpression == null) ? this.values : asIterable(target.access(collectionExpression));
			// note that we allow null to match null
			for (var value: values)
			{
				if (value instanceof Reference)
				{
					value = ((Reference) value).access(target);
				}
				if ((testee == null) && (value == null))
				{
					return !isInverted;
				}
				// make sure we can match objects like Enums with Strings
				if ((testee != null) && (value != null) && value.equals((value instanceof String) ? testee.toString() : testee))
				{
					return !isInverted;
				}
			}
			return isInverted;
		}
	}

	/**
	 * The Range selector tests an attribute against a lower and/or upper bound.
	 * Variants:
	 * <dl>
	 *     <dt><tt>between("expression", lower, upper)</tt></dt>
	 *     <dd>Checks if the result of evaluating the attribute expression is inside the closed interval given by the given lower and upper bounds.</dd>
	 *     <dt><tt>outside("expression", lower, upper)</tt></dt>
	 *     <dd>Checks if the result of evaluating the attribute expression is outside the closed interval given by the lower and upper bounds.</dd>
	 *     <dt><tt>ge("expression", value)</tt></dt>
	 *     <dd>Checks if the result of evaluating the attribute expression is greater than or equal to the given value.</dd>
	 *     <dt><tt>gt("expression", value)</tt></dt>
	 *     <dd>Checks if the result of evaluating the attribute expression is greater than the given value.</dd>
	 *     <dt><tt>le("expression", value)</tt></dt>
	 *     <dd>Checks if the result of evaluating the attribute expression is less than or equal to the given value.</dd>
	 *     <dt><tt>lt("expression", value)</tt></dt>
	 *     <dd>Checks if the result of evaluating the attribute expression is less than the given value.</dd>
	 * </dl>
	 */
	public static class RangeSelector extends AttributeSelector
	{
		private final Object lower;
		private final Object upper;

		RangeSelector(String expression, Object lower, Object upper, boolean isInverted)
		{
			super(expression, isInverted);
			this.lower = lower;
			this.upper = upper;
		}

		@SuppressWarnings("unchecked")
		@Override
		boolean matches(IAccessor target)
		{
			var attribute = (Comparable<Object>) target.access(expression);
			if (attribute == null)
			{
				return false;
			}
			if (lower != null)
			{
				var lower = (this.lower instanceof Reference) ? ((Reference) this.lower).access(target) : this.lower;
				if (lower == null)
				{
					// if we got here, the reference returned null
					return false;
				}
				if (attribute.compareTo(lower) < 0)
				{
					return isInverted;
				}
			}
			if (upper != null)
			{
				var upper = (this.upper instanceof Reference) ? ((Reference) this.upper).access(target) : this.upper;
				if (upper == null)
				{
					// if we got here, the reference returned null
					return false;
				}
				if (attribute.compareTo(upper) > 0)
				{
					return isInverted;
				}
			}
			return !isInverted;
		}
	}

	/**
	 * The Like selector checks an attribute against one or more regular expressions.
	 * Variants:
	 * <dl>
	 *     <dt><tt>like("expression", regexp...)</tt></dt>
	 *     <dd>Checks if the result of evaluating the attribute expression matches one of the provided regular expressions.</dd>
	 *     <dt><tt>unlike("expression", regexp...)</tt></dt>
	 *     <dd>Checks if the result of evaluating the attribute expression does not match any of the provided regular expressions.</dd>
	 * </dl>
	 */
	public static class LikeSelector extends AttributeSelector
	{
		private final Object[] values;

		LikeSelector(String expression, Object[] values, boolean isInverted)
		{
			super(expression, isInverted);
			this.values = new Object[values.length];
			for (var index = 0; index < values.length; ++index)
			{
				var value = values[index];
				if ((value != null) && (!(value instanceof Reference)))
				{
					value = Pattern.compile(value.toString());
				}
				this.values[index] = value;
			}
		}

		@Override
		boolean matches(IAccessor target)
		{
			var attribute = target.access(expression);
			var testee = (attribute == null) ? null : attribute.toString();
			// note that we allow null to match null
			for (var value: values)
			{
				Pattern pattern;
				if (value instanceof Reference)
				{
					value = ((Reference) value).access(target);
					pattern = (value == null) ? null : Pattern.compile(Pattern.quote(value.toString()));
				}
				else
				{
					pattern = (Pattern) value;
				}
				if ((testee == null) && (pattern == null))
				{
					return !isInverted;
				}
				if ((testee != null) && (pattern != null) && pattern.matcher(testee).find())
				{
					return !isInverted;
				}
			}
			return isInverted;
		}
	}

	/**
	 * The Any/All selector tests the elements of a collection attribute against zero
	 * or more selectors. The selectors are ANDed. Variants:
	 * <dl>
	 *     <dt><tt>any("expression", selector...)</tt></dt>
	 *     <dd>Checks if the collection resulting from evaluating the attribute expression contains an element that matches all of the selectors.</dd>
	 *     <dt><tt>none("expression", value...)</tt></dt>
	 *     <dd>Checks if the collection resulting from evaluating the attribute expression contains no element that matches all of the selectors.</dd>
	 *     <dt><tt>all("expression", selector...)</tt></dt>
	 *     <dd>Checks if the collection resulting from evaluating the attribute expression is missing or empty or contains only elements that match all of the selectors.</dd>
	 *     <dt><tt>notall("expression", value...)</tt></dt>
	 *     <dd>Checks if the collection resulting from evaluating the attribute expression contains any element that does not match all of the selectors.</dd>
	 * </dl>
	 */
	public static class AnyAllSelector extends CollectionSelector
	{
		private final boolean isAny;

		AnyAllSelector(String expression, Selector[] selectors, boolean isAny, boolean isInverted)
		{
			super(expression, new AndOrSelector(selectors, true, false), isInverted);
			this.isAny = isAny;
		}

		@Override
		boolean matches(IAccessor target)
		{
			var collection = asIterable(target.access(expression));
			for (var element: collection)
			{
				if (!(element instanceof IAccessor))
				{
					element = target.derive(element);
				}
				if (selector.matches((IAccessor) element) == isAny)
				{
					return isAny ^ isInverted;
				}
			}
			return isAny ^ !isInverted;
		}
	}

	/**
	 * Counting selectors check the number of elements of an iterable satisfying
	 * the given selectors against a lower and/or upper bound. The selectors are
	 * ANDed. Variants:
	 * <dl>
	 *     <dt><tt>mt(min, "expression", selector...)</tt></dt>
	 *     <dd>Checks if the collection resulting from evaluating the attribute expression contains more than min elements that match all of the selectors.</dd>
	 *     <dt><tt>me(min, "expression", selector...)</tt></dt>
	 *     <dd>Checks if the collection resulting from evaluating the attribute expression contains more or equal than than min elements that match all of the selectors.</dd>
	 *     <dt><tt>ft(max, "expression", selector...)</tt></dt>
	 *     <dd>Checks if the collection resulting from evaluating the attribute expression contains fewer than max elements that match all of the selectors.</dd>
	 *     <dt><tt>fe(max, "expression", selector...)</tt></dt>
	 *     <dd>Checks if the collection resulting from evaluating the attribute expression contains fewer or equal than max elements that match all of the selectors.</dd>
	 *     <dt><tt>num(min, max, "expression", selector...)</tt></dt>
	 *     <dd>Checks if the collection resulting from evaluating the attribute expression contains between min and max elements (inclusive) that match all of the selectors.</dd>
	 * </dl>
	 */
	public static class CountingSelector extends CollectionSelector
	{
		private final Object lower;
		private final Object upper;

		CountingSelector(String expression, Object lower, Object upper, Selector[] selectors, boolean isInverted)
		{
			super(expression, new AndOrSelector(selectors, true, false), isInverted);
			this.lower = lower;
			this.upper = upper;
		}

		@Override
		boolean matches(IAccessor target)
		{
			var lower = (Integer) ((this.lower instanceof Reference) ? ((Reference) this.lower).access(target) : this.lower);
			var upper = (Integer) ((this.upper instanceof Reference) ? ((Reference) this.upper).access(target) : this.upper);
			var collection = asIterable(target.access(expression));
			var count = 0;
			for (var element: collection)
			{
				if (!(element instanceof IAccessor))
				{
					element = target.derive(element);
				}
				if (selector.matches((IAccessor) element))
				{
					++count;
				}
				if (upper != null)
				{
					if (count > upper)
					{
						return isInverted;
					}
				}
				else
				{
					if ((lower != null) && (count >= lower))
					{
						return !isInverted;
					}
				}
			}
			return ((lower == null) || (count >= lower)) ^ isInverted;
		}
	}

	/**
	 * The And/Or selector combines the results of the selectors given to it. Variants:
	 * <dl>
	 *     <dt><tt>and(selector...)</tt></dt>
	 *     <dd>Checks if the object matches all selectors.</dd>
	 *     <dt><tt>nand(selector...)</tt></dt>
	 *     <dd>Negated AND, equal to <tt>not( and(...) )</tt>: Checks if the object matches not all selectors.</dd>
	 *     <dt><tt>or(selector...)</tt></dt>
	 *     <dd>Checks if the object matches any of the selectors.</dd>
	 *     <dt><tt>nor(selector...)</tt></dt>
	 *     <dd>Negated OR, equal to <tt>not( or(...) )</tt>: Checks if the object does not match any of the selectors.</dd>
	 *     <dt><tt>not(selector)</tt></dt>
	 *     <dd>Negation: Checks if the object does not match the selector.</dd>
	 * </dl>
	 */
	public static class AndOrSelector extends CompositeSelector
	{
		private final boolean isAnd;

		AndOrSelector(Selector[] selectors, boolean isAnd, boolean isInverted)
		{
			super(selectors, isInverted);
			this.isAnd = isAnd;
		}

		@Override
		boolean matches(IAccessor target)
		{
			for (var selector: selectors) {
				if (selector.matches(target) != isAnd)
				{
					return isAnd ^ !isInverted;
				}
			}
			return isAnd ^ isInverted;
		}
	}

	/**
	 * Objects of this class are used in place of literals to use the value represented by
	 * an attribute expression instead of a fixed value.
	 */
	public static class Reference
	{
		private final String expression;

		public Reference(String expression)
		{
			this.expression = expression;
		}

		Object access(IAccessor target)
		{
			return target.access(expression);
		}
	}

	/**
	 * Returned from Rule evaluation ({@link RuleSet#approve(IAccessor)}). Reports
	 * using member variables if the object was accepted or rejected by the RuleSet,
	 * as well as the Rule and Action that triggered the decision.
	 */
	public static class Decision
	{
		private final String ruleId;
		private final String actionId;
		private final boolean isAccept;

		public Decision(String ruleId, String actionId, boolean isAccept)
		{
			this.ruleId = ruleId;
			this.actionId = actionId;
			this.isAccept = isAccept;
		}

		public String getRuleId()
		{
			return ruleId;
		}

		public String getActionId()
		{
			return actionId;
		}

		public boolean isAccept()
		{
			return isAccept;
		}
	}

	/**
	 * Objects of this class represent a single rule. Rules are created by the following
	 * DSL construct (rule header):
	 * <pre><i>RuleName</i>(...)</pre>
	 * Here, <i>RuleName</i> is whatever the application chooses for representing rules
	 * in the DSL notation - often the class name of the objects to be checked like
	 * <tt>Bundle</tt> or <tt>Identity</tt>.
	 */
	public static class Rule
	{
		/**
		 * <p>Action objects represent a rule action (decision condition). They are
		 * created by appending one of the following constructs to a rule header
		 * (infinitely chainable):
		 * </p>
		 * <tt>
		 * .accept(...)<br>
		 * .reject(...)<br>
		 * .when(...)
		 * </tt>
		 */
		private static class Action
		{
			private final String id;
			private final boolean isAccept;
			private final Selector selector;

			private Action(String id, boolean isAccept, Selector[] selectors)
			{
				this.id = id;
				this.isAccept = isAccept;
				selector = new AndOrSelector(selectors, true, false);
			}

			/**
			 * Determine if the action is to be fired
			 */
			private boolean matches(IAccessor target)
			{
				return selector.matches(target);
			}
		}

		private final String id;
		private final Selector selector;
		private final List<Action> actions = new ArrayList<>();

		private Rule(String id, Selector[] selectors)
		{
			this.id = id;
			selector = new AndOrSelector(selectors, true, false);
		}

		/**
		 * Return the rule ID
		 */
		public String getId()
		{
			return id;
		}

		/**
		 * Determine if the rule applies to the given object
		 */
		private boolean matches(IAccessor target)
		{
			return selector.matches(target);
		}

		/**
		 * Add an "accept" action (positive decision) to this rule
		 * @param actionId action ID
		 * @param selectors action selectors
		 */
		public Rule accept(String actionId, Selector... selectors)
		{
			actions.add(new Action(actionId, true, selectors));
			return this;
		}

		/**
		 * Variant of {@link #accept(String, Selector...)} setting the <i>action ID</i> to <tt>null</tt>
		 */
		public Rule accept(Selector... selectors)
		{
			return accept(null, selectors);
		}

		/**
		 * Add a "reject" action (negative decision) to this rule
		 * @param actionId action ID
		 * @param selectors action selectors
		 */
		public Rule reject(String actionId, Selector... selectors)
		{
			actions.add(new Action(actionId, false, selectors));
			return this;
		}

		/**
		 * Variant of {@link #reject(String, Selector...)} setting the <i>action ID</i> to <tt>null</tt>
		 */
		public Rule reject(Selector... selectors)
		{
			return reject(null, selectors);
		}

		/**
		 * Alternative notation for {@link #accept(String, Selector...)}
		 */
		public Rule when(String actionId, Selector... selectors)
		{
			return accept(actionId, selectors);
		}

		/**
		 * Alternative notation for {@link #reject(String, Selector...)}
		 */
		public Rule fail(String actionId, Selector... selectors)
		{
			return reject(actionId, selectors);
		}

		/**
		 * Check the rule for any action to be fired. If none, <tt>null</tt> is returned,
		 * otherwise an {@link Decision} object.
		 */
		private Decision approve(IAccessor target)
		{
			for (var action: actions)
			{
				if (action.matches(target))
				{
					return new Decision(id, action.id, action.isAccept);
				}
			}
			return null;
		}

		/**
		 * Compute the value this rule stands for and add it to the result map
		 */
		private void evaluate(IAccessor target, Map<String, Object> result, boolean evaluate)
		{
			for (var action: actions)
			{
				if (action.matches(target))
				{
					if (action.isAccept)
					{
						result.put(id, evaluate ? target.access(action.id) : action.id);
					}
					else
					{
						throw new RuntimeException(String.format("Validation failed for %s: %s", id, action.id));
					}
					break;
				}
			}
		}
	}

	/**
	 * <p>RuleSets are what results from compiling CerberusLogic code. They are also
	 * created from existing RuleSets by the {@link #select(IAccessor)} method.
	 * </p>
	 * <p>A RuleSet contains a configuration map and a list of Rules. Both are optional,
	 * however the whole purpose of CerberusLogic is to <i>have</i> Rules to evaluate
	 * at some point.
	 * </p>
	 * <p>RuleSets are applied to objects using the {@link #select(IAccessor)} and
	 * {@link #approve(IAccessor)} methods.
	 * </p>
	 */
	public static class RuleSet
	{
		private final List<Rule> rules;
		private final HashMap<String, Object> config;

		public RuleSet()
		{
			this.rules = new ArrayList<>();
			this.config = new HashMap<>();
		}

		/**
		 * Return the RuleSet's configuration. Besides the contents of the optional
		 * <tt>config()</tt> DSL directive, it will also contain - under the key
		 * <tt>ruleTag</tt> - the requested or autodetected <tt>ruleTag</tt>.
		 */
		public Map<String, Object> getConfig()
		{
			return config;
		}

		/**
		 * Return the RuleSet's list of rules
		 */
		public List<Rule> getRules()
		{
			return rules;
		}

		/**
		 * Add a new rule to the end of this RuleSet
		 * @param ruleId rule ID
		 * @param selectors rule selectors
		 * @return rule that was added
		 */
		public Rule addRule(String ruleId, Selector... selectors)
		{
			var rule = new Rule(ruleId, selectors);
			rules.add(rule);
			return rule;
		}

		/**
		 * Add an existing rule to the end of this RuleSet
		 * @param rule rule to add
		 */
		public void addRule(Rule rule)
		{
			rules.add(rule);
		}

		/**
		 * Select the Rules from the set that are - according to their selectors -
		 * applicable to <tt>target</tt> and return them as a new <tt>RuleSet</tt>.
		 * If no Rules apply, <tt>null</tt> is returned.
		 */
		public RuleSet select(IAccessor target)
		{
			var result = new RuleSet();
			for (var rule: rules)
			{
				if (rule.matches(target))
				{
					result.addRule(rule);
				}
			}
			return (result.getRules().isEmpty()) ? null : result;
		}

		/**
		 * Check <tt>target</tt> against the actions of all Rules and return the decision,
		 * if one was made.
		 */
		public Decision approve(IAccessor target)
		{
			for (var rule: rules)
			{
				var decision = rule.approve(target);
				if (decision != null)
				{
					return decision;
				}
			}
			return null;
		}

		/**
		 * Compute the values defined by this rule set for <tt>source</tt>
		 *
		 * @param source input object
		 * @return Map, keyed by rule ID, containing the values computed from the <tt>source</tt> object by each rule
		 */
		public Map<String, Object> evaluate(IAccessor source, boolean evaluate)
		{
			var result = new HashMap<String, Object>();
			for (var rule: rules)
			{
				rule.evaluate(source, result, evaluate);
			}
			return result;
		}
	}

	private static class Compiler
	{
		private final StringBuilder source;
		private String ruleTag;
		private final Map<String, String> macroLibrary;
		private int position = 0;

		private Compiler(StringBuilder source, String ruleTag, Map<String, String> macroLibrary)
		{
			this.source = source;
			this.ruleTag = ruleTag;
			this.macroLibrary = macroLibrary;
		}

		private RuleSet parseRuleSet()
		{
			var ruleSet = new RuleSet();
			var config = ruleSet.getConfig();
			parseConfig(config);
			if (ruleTag == null)
			{
				ruleTag = (String) config.get("ruleTag");
			}
			while (parseRule(ruleSet))
			{
				locateNextToken();
			}
			config.put("ruleTag", ruleTag);
			return ruleSet;
		}

		private void parseConfig(Map<String, Object> result)
		{
			var start = position;
			var identifier = readIdentifier();
			if ("config".equals(identifier))
			{
				if (!checkDelimiter("(", true))
				{
					throw new RuntimeException("Malformed config section");
				}
				parseConfigMap(result);
				if (!checkDelimiter(")", true))
				{
					throw new RuntimeException("Config section not properly terminated");
				}
				locateNextToken();
				if (checkDelimiter(";", true))
				{
					locateNextToken();
				}
			}
			else
			{
				position = start;
			}
		}

		private void parseConfigMap(Map<String, Object> result)
		{
			locateNextToken();
			while (true)
			{
				var entryName = readIdentifier();
				if (entryName == null)
				{
					break;
				}
				if (!checkDelimiter("(", true))
				{
					throw new RuntimeException("Malformed config entry: " + entryName);
				}
				result.put(entryName, parseConfigValue());
				if (!checkDelimiter(")", true))
				{
					throw new RuntimeException("Config entry not properly terminated: " + entryName);
				}
				locateNextToken();
				if (!checkDelimiter(",", true))
				{
					break;
				}
				locateNextToken();
				if (checkDelimiter(")", false))
				{
					throw new RuntimeException("Config entry expected at position " + position);
				}
			}
		}

		private Object parseConfigValue()
		{
			locateNextToken();
			var start = position;
			if (readIdentifier() != null)
			{
				// a named value indicates that the config value is a Map
				position = start;
				var result = new HashMap<String, Object>();
				parseConfigMap(result);
				return result;
			}
			else
			{
				if (checkDelimiter(")", false))
				{
					return null;
				}
				var values = readArguments(false);
				return (values.size() == 1) ? values.get(0) : values;
			}
		}

		private boolean parseRule(RuleSet ruleSet)
		{
			locateNextToken();
			var start = position;
			var tag = readIdentifier();
			if (tag == null)
			{
				if (position < source.length())
				{
					throw new RuntimeException(String.format("Rule header expected at position %d: %s", position, source.substring(position)));
				}
				return false;
			}
			if ((ruleTag == null) && Character.isUpperCase(tag.charAt(0)))
			{
				ruleTag = tag;
			}
			if (!tag.equals(ruleTag) || !checkDelimiter("(", true))
			{
				throw new RuntimeException("Invalid rule header: " + tag);
			}
			var arguments = readArguments(true);
			locateNextToken();
			if (!checkDelimiter(")", true))
			{
				throw new RuntimeException("Rule header not properly terminated: " + source.substring(start, position));
			}
			locateNextToken();
			var ruleId = (arguments.isEmpty() || (arguments.get(0) instanceof Selector)) ? null : arguments.remove(0).toString();
			var rule = ruleSet.addRule(ruleId, Selector.slice(Selector.class, arguments.toArray(), 0));
			while (checkDelimiter(".", true))
			{
				parseAction(rule);
			}
			checkDelimiter(";", true);
			return true;
		}

		private void parseAction(Rule rule)
		{
			locateNextToken();
			var start = position;
			var tag = readIdentifier();
			if ((tag == null) || !checkDelimiter("(", true))
			{
				throw new RuntimeException("Malformed action at position " + position);
			}
			var isAccept = switch (tag)
			{
				case "accept", "when" -> true;
				case "reject", "fail" -> false;
				default -> throw new RuntimeException("Invalid action: " + tag);
			};
			var arguments = readArguments(true);
			locateNextToken();
			if (!checkDelimiter(")", true))
			{
				throw new RuntimeException("Action not properly terminated: " + source.substring(start, position));
			}
			locateNextToken();
			var actionId = (arguments.isEmpty() || !(arguments.get(0) instanceof String)) ? null : (String) arguments.remove(0);
			if (isAccept)
			{
				rule.accept(actionId, Selector.slice(Selector.class, arguments.toArray(), 0));
			}
			else
			{
				rule.reject(actionId, Selector.slice(Selector.class, arguments.toArray(), 0));
			}
		}

		private String readIdentifier()
		{
			locateNextToken();
			var start = position;
			while ((position < source.length()) && Character.isJavaIdentifierStart(source.codePointAt(position)))
			{
				++position;
			}
			return (position > start) ? source.substring(start, position) : null;
		}

		private Object readArgument(boolean allowComplexType)
		{
			locateNextToken();
			var start = position;
			if (allowComplexType)
			{
				var name = readIdentifier();
				if ((name != null) && checkDelimiter("(", true))
				{
					var arguments = readArguments(true);
					locateNextToken();
					if (!checkDelimiter(")", true))
					{
						// omitting special handling for ref()
						throw new RuntimeException("Selector not properly terminated: " + name);
					}
					locateNextToken();
					return Selector.construct(name, arguments.toArray());
				}
				position = start;
			}
			if (checkDelimiter("\"", true))
			{
				// read string literal
				var result = new StringBuilder();
				var matcher = Pattern.compile("\\\\.|\"").matcher(source);
				while (matcher.find(position))
				{
					result.append(source, position, matcher.start());
					// detect end of string literal
					if ("\"".equals(matcher.group()))
					{
						position = matcher.start();
						break;
					}
					var character = matcher.group().charAt(1);
					// translate escape sequence
					if (character == 'n')
					{
						character = '\n';
					}
					result.append(character);
					position = matcher.end();
				}
				if (!checkDelimiter("\"", true))
				{
					throw new RuntimeException("String literal not properly terminated: " + source.substring(start));
				}
				locateNextToken();
				return result.toString();
			}
			else
			{
				// read number, boolean or null
				while (position < source.length())
				{
					int character = source.codePointAt(position);
					if (!Character.isLetterOrDigit(character) && (character != '.') && (character != '-'))
					{
						break;
					}
					++position;
				}
				var value = source.substring(start, position);
				locateNextToken();
				return switch (value)
				{
					case "true" -> true;
					case "false" -> false;
					case "null" -> null;
					case "" -> throw new RuntimeException("Value expected at position " + start);
					default -> (value.contains(".")) ? (Object) Double.parseDouble(value) : (Object) Integer.parseInt(value);
				};
			}
		}

		@SuppressWarnings("ConditionalBreakInInfiniteLoop")
		private List<Object> readArguments(boolean allowComplex)
		{
			locateNextToken();
			var result = new ArrayList<>();
			if (!checkDelimiter(")", false))
			{
				while (true)
				{
					result.add(readArgument(allowComplex));
					if (!checkDelimiter(",", true))
					{
						break;
					}
				}
			}
			return result;
		}

		private void locateNextToken()
		{
			while (position < source.length())
			{
				if (Character.isWhitespace(source.codePointAt(position)))
				{
					// skip whitespace
					++position;
				}
				else if ((source.length() > position + 1) && "//".equals(source.substring(position, position + 2)))
				{
					// skip comments
					var endOfLine = source.indexOf("\n", position);
					position = (endOfLine < 0) ? source.length() : endOfLine + 1;
				}
				else if ((source.length() > position) && (source.charAt(position) == '$'))
				{
					// macro expansion
					var end = source.indexOf("$", position + 1);
					if (end < 0)
					{
						throw new RuntimeException(String.format("Invalid macro reference at position %d", position));
					}
					var name = source.substring(position + 1, end);
					var expansion = macroLibrary.get(name);
					if (expansion == null)
					{
						throw new RuntimeException(String.format("Unknown macro: %s", name));
					}
					source.replace(position, end + 1, expansion);
				}
				else
				{
					break;
				}
			}
		}

		@SuppressWarnings("BooleanMethodIsAlwaysInverted")
		private boolean checkDelimiter(String delimiter, boolean skip)
		{
			if ((source.length() < position + delimiter.length()) || !delimiter.equals(source.substring(position, position + delimiter.length())))
			{
				return false;
			}
			if (skip)
			{
				position += delimiter.length();
			}
			return true;
		}
	}

	/**
	 * Variant of {@link #compile(CharSequence, String, Map)} that does not use a macro library
	 */
	public static RuleSet compile(CharSequence source, String ruleTag)
	{
		return compile(source, ruleTag, Collections.emptyMap());
	}

	/**
	 * <p>Compile CerberusLogic code into a {@link RuleSet}
	 * @param source DSL source code; if given as StringBuilder, it will be updated by the macro expansions
	 * @param ruleTag identifier expected to be used for the rule headers, will be autodetected if <tt>null</tt>
	 * @param macroLibrary a Map that links macro names to their expansions   
	 */
	public static RuleSet compile(CharSequence source, String ruleTag, Map<String, String> macroLibrary)
	{
		var compiler = new Compiler((source instanceof StringBuilder) ? (StringBuilder) source : new StringBuilder(source), ruleTag, macroLibrary);
		return compiler.parseRuleSet();
	}

	/**
	 * Variant of {@link #compileSelector(CharSequence, Map)} that does not use a macro library
	 */
	public static Selector compileSelector(CharSequence source)
	{
		return compileSelector(source, Collections.emptyMap());
	}

	/**
	 * Compile a CerberusLogic selector from the provided source code
	 * @param source DSL source code; if given as StringBuilder, it will be updated by the macro expansions
	 * @param macroLibrary a Map that links macro names to their expansions
	 */
	public static Selector compileSelector(CharSequence source, Map<String, String> macroLibrary)
	{
		var compiler = new Compiler(new StringBuilder(source), null, macroLibrary);
		var result = compiler.readArgument(true);
		if (result instanceof Selector)
		{
			return (Selector) result;
		}
		throw new RuntimeException("Invalid selector: " + source);
	}
}
