package com.six.iam.util;

import org.apache.commons.lang.StringEscapeUtils;
import org.jetbrains.annotations.NotNull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.*;
import java.util.*;
import java.util.Map;
import java.util.function.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import sailpoint.api.SailPointContext;
import sailpoint.object.*;
import sailpoint.tools.CloseableIterator;
import sailpoint.tools.GeneralException;
import sailpoint.tools.xml.XMLObjectFactory;

/**
 * This is the implementation of the OrionQL mini language for querying objects.
 * OrionQL expressions are Strings opaque to the application program and are
 * interpreted solely by the code below. The main features of OrionQL include:
 * <ul>
 *     <li>Attribute access<br>Example: <tt>assignedRoles</tt></li>
 *     <li>Chained attribute access<br>Example: <tt>owner.displayName</tt></li>
 *     <li>Processing instructions<br>Example: <tt>assignedRoles:map(name)</tt></li>
 *     <li>Compound expressions<br>Example: <tt>displayName|name</tt></li>
 * </ul>
 * In addition, the template substitution is available directly by flagging an
 * OrionQL expression as template.
 */
public class OrionQL
{
	/**
	 * <p>This class wraps objects of type T to allow OrionQL expressions to be
	 * evaluated on them. By implementing the {@link com.six.iam.util.CerberusLogic.IAccessor}
	 * interface, it allows to submit objects to CerberusLogic Rule evaluations
	 * but is used in other places as well.
	 * </p>
	 * <p>Objects are wrapped into a proxy by either calling the static OrionQL
	 * method {@link #wrap(Object, SailPointContext)} or - from an existing proxy -
	 * calling {@link #derive(Object)}. Derived proxies share some common data like
	 * the SailPointContext needed for operation as well as caches or the formatting
	 * template.
	 * </p>
	 * <p>OrionQL expressions are evaluated by calling either {@link #access(String)}
	 * on the proxy or {@link Accessor#access(Proxy)} from a precompiled OrionQL
	 * expression. There are some convenience methods as well.
	 * </p>
	 */
	public static class Proxy<T> implements CerberusLogic.IAccessor
	{
		private final Proxy<?> parent;
		private final T payload;
		private final Map<String, Object> overrides = new HashMap<>();
		private final SailPointContext resolver;
		private final Map<String, Accessor> compilationCache;
		private final Map<String, String> macroLibrary;
		private final Map<String,Map<Object,Object>> lookupTables;
		private final Map<Object,Object> userCache;
		private final Map<String,Object> environment;
		private Accessor format;

		private Proxy(Proxy<?> parent, T payload, SailPointContext resolver, Map<String, Accessor> compilationCache, Map<String, String> macroLibrary, Map<String,Object> environment, Map<String,Map<Object,Object>> lookupTables, Map<Object,Object> userCache, Accessor format) {
			this.parent = parent;
			this.payload = payload;
			this.resolver = resolver;
			this.compilationCache = compilationCache;
			this.macroLibrary = macroLibrary;
			this.environment = environment;
			this.lookupTables = lookupTables;
			this.userCache = userCache;
			this.format = format;
		}

		private Proxy(Proxy<?> parent, T payload, SailPointContext resolver, Map<String, String> macroLibrary, Map<String,Object> environment) {
			this(parent, payload, resolver, new HashMap<>(), macroLibrary, environment, new HashMap<>(), new HashMap<>(), null);
		}

		/**
		 * Add the given object as a virtual attribute to the proxied object. Any real
		 * attribute with the same name will be shadowed.
		 */
		public Proxy<T> implant(String attributeName, Object value)
		{
			overrides.put(attributeName, value);
			return this;
		}

		/**
		 * Attach a lookup table to be used by the lookup processing instructions.
		 * This replaces any table previously defined for this name. Lookup tables will
		 * be shared by all proxies derived from a common parent.
		 */
		@Deprecated
		public Proxy<T> setLookupTable(String name, Map<Object, Object> lookupTable)
		{
			lookupTables.put(name, lookupTable);
			return this;
		}

		/**
		 * Discard all cached data
		 */
		public void clearCache()
		{
			for (var key: lookupTables.keySet().stream().filter(s -> !s.isEmpty() && Character.isUpperCase(s.charAt(0))).collect(Collectors.toList()))
			{
				lookupTables.remove(key);
			}
			userCache.clear();
		}

		/**
		 * Return the proxied object
		 */
		public T unwrap()
		{
			return payload;
		}

		/**
		 * Return a proxy wrapping the given object if it is not already a proxy.
		 * Derived proxies form an interconnected structure sharing common data
		 * like caches and formatting template and facilitate parent navigation.
		 */
		@SuppressWarnings("unchecked")
		@Override
		public <V> Proxy<V> derive(V payload)
		{
			if (payload instanceof Proxy)
			{
				return (Proxy<V>) payload;
			}
			return new Proxy<>(this, payload, resolver, compilationCache, macroLibrary, environment, lookupTables, userCache, format);
		}

		/**
		 * Return the result of evaluating the given expression. The compiled expression
		 * will be cached for future use by all proxies derived from the same ancestor.
		 */
		@Override
		public <V> V access(String expression)
		{
			return compile(expression, false).access(this);
		}

		/**
		 * Format the object represented by this proxy into a string by doing placeholder
		 * substitution on the given template. The compiled version of the template string
		 * will be cached for future use by all proxies derived from the same ancestor.
		 */
		public String format(String template)
		{
			return compile(template, true).access(this);
		}

		/**
		 * Compile an OrionQL expression or template string. The compiled version
		 * will also be cached for future use by all proxies derived from the same ancestor.
		 */
		public Accessor compile(String expression, boolean isTemplate)
		{
			return compilationCache.computeIfAbsent(
					// ensure templates cannot collide with proper expressions
					isTemplate ? ":." + expression : expression,
					key -> OrionQL.compile(expression, isTemplate, macroLibrary));
		}

		/**
		 * Set a formatting template of the form that is accepted by the {@link #format(String)}
		 * method to control the behaviour of the {@link #toString()} method. Proxies created
		 * using the {@link #derive(Object)} method will inherit the template of the parent proxy,
		 * however setting a different template is always possible and will not affect other proxies.
		 */
		public Proxy<T> setFormat(String format)
		{
			this.format = compile(format, true);
			return this;
		}

		@Override
		public String toString()
		{
			return (format != null) ? format.access(this) : "Proxy for " + payload;
		}
	}

	/**
	 * Objects of this class represent compiled OrionQL expressions or templates and are
	 * created by one of the compile() methods like {@link #compile(CharSequence, boolean)},
	 * {@link #compile(CharSequence, boolean, Map)} or {@link Proxy#compile(String, boolean)}.
	 * The only public API method is {@link #access(Proxy)}, which returns the result of applying
	 * the expression or template to the object given to it (wrapped into a {@link Proxy}).
	 */
	public abstract static class Accessor
	{
		/**
		 * This method is to be implemented by the subclasses. It reads the accessor
		 * specification from <tt>expression</tt> and appends the accessor created to <tt>result</tt>.
		 * Upon entry, <tt>position</tt> points to the first character of the specification
		 * right after the leading . or :. Upon exit, <tt>position</tt> points behind
		 * the end marker found or behind the end of input. The end marker is returned.
		 *
		 * @param expression    the expression under parsing, will be updated by macro expansions
		 * @param position      the current parse position in it, will be updated
		 * @param endMarkers    the regular expression signaling the end of the construct to parse
		 * @param macroLibrary  a Map that links macro names to their expansions
		 * @param result        the accessor resulting from the parse will be added to this list
		 * @return the delimiter matched by <tt>endMarkers</tt> or <tt>null</tt> when EOI was reached
		 */
		@SuppressWarnings("unused")
		static String parse(StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			throw new RuntimeException("Abstract method called");
		}

		/**
		 * Check if the expression continues with the specified string
		 *
		 * @param expression the expression under parsing
		 * @param position   the current position
		 * @param prefix     the string to check for
		 * @return <tt>true</tt> if at <tt>position</tt> <tt>expression</tt> contains <tt>prefix</tt>
		 */
		static boolean startsWith(StringBuilder expression, ParsePosition position, String prefix)
		{
			var start = position.getIndex();
			if (expression.length() < start + prefix.length())
			{
				return false;
			}
			return prefix.equals(expression.substring(start, start + prefix.length()));
		}

		/**
		 * Replace the macro reference starting at <tt>position</tt>, if present, with the expansion.
		 *
		 * @param input         the input text
		 * @param position      the position in it to look for the macro reference, will stay unchanged
		 * @param macroLibrary  a Map that links macro names to their expansions
		 * @return <tt>true</tt> if a macro substitution was performed
		 */
		static boolean expandMacro(StringBuilder input, ParsePosition position, Map<String, String> macroLibrary)
		{
			if (!startsWith(input, position, "$") || startsWith(input, position, "${") || startsWith(input, position, "$\""))
			{
				return false;
			}
			var start = position.getIndex();
			position.setIndex(start + 1);
			var buffer = new StringBuilder();
			var delimiter = readInput(input, position, "\\$", false, buffer);
			if (delimiter == null)
			{
				throw new RuntimeException(String.format("Unclosed macro reference at position %d of `%s`: %s", start, input, input.substring(start)));
			}
			var expansion = macroLibrary.get(buffer.toString());
			if (expansion == null)
			{
				throw new RuntimeException(String.format("Unknown macro at position %d of `%s`: %s", start, input, buffer));
			}
			input.replace(start, position.getIndex(), expansion);
			position.setIndex(start);
			return true;
		}

		/**
		 * Read text from input into the provided StringBuilder, handling escape sequences
		 * and stopping at the first match of the optional regular expression
		 *
		 * @param input           the input to read from starting at <tt>position</tt>
		 * @param position        the input position at which to start reading, will be updated
		 *                        to point to the position after the matched delimiter or after EOI
		 * @param delimiterRegexp the regular expression identifying the end of the text to read
		 * @param allowComments   set this to <tt>true</tt> when parsing DSL syntax and to false for reading text
		 * @param result          the buffer to receive the text read from input
		 * @return the text matched by the delimiter expression or <tt>null</tt> at EOI
		 */
		@SuppressWarnings("MagicCharacter")
		static String readInput(StringBuilder input, ParsePosition position, String delimiterRegexp, boolean allowComments, StringBuilder result)
		{
			var matcher = Pattern.compile(combineRE(
					combineRE("\\\\[\\r\\n]\\s*|\\\\[0-9A-Fa-f]{4}|\\\\.", allowComments ? " *--[^;\\n]*(?:[;\\n]\\s*)?" : null),
					delimiterRegexp)
			).matcher(input);
			while (true)
			{
				String delimiter = null;
				if (matcher.find(position.getIndex()))
				{
					result.append(input, position.getIndex(), matcher.start());
					position.setIndex(matcher.start());
					delimiter = matcher.group();
					if (!delimiter.matches(delimiterRegexp))
					{
						if (delimiter.startsWith("\\"))
						{
							var character = delimiter.charAt(1);
							if ((character == '\r') || (character == '\n'))
							{
								// hidden linebreak -> remove from input
								input.replace(matcher.start(), matcher.end(), "");
								continue;
							}
							else if (character == 'n')
							{
								// escape sequence
								character = '\n';
							}
							else if (delimiter.length() == 5)
							{
								// escape sequence
								character = (char) Integer.parseInt(delimiter, 1, 5, 16);
							}
							result.append(character);
							position.setIndex(matcher.end());
						}
						else
						{
							// comment -> remove from input
							input.replace(matcher.start(), matcher.end(), "");
						}
						continue;
					}
					// if we are here, it was delimiterRegexp that matched -> done
					position.setIndex(matcher.end());
				}
				else
				{
					result.append(input.substring(position.getIndex()));
					position.setIndex(input.length());
				}
				// text complete
				return delimiter;
			}
		}

		/**
		 * Read text from input into the provided StringBuilder until the delimiter regular
		 * expression appears outside any parenthesized construct or string literal
		 *
		 * @param input           the input to read from starting at <tt>position</tt>
		 * @param position        the input position at which to start reading, will be updated
		 *                        to point to the position after the matched delimiter or after EOI
		 * @param delimiterRegexp the regular expression identifying the end of the text to read
		 * @param result          the buffer to receive the text read from input
		 * @return the text matched by the delimiter expression or <tt>null</tt> at EOI
		 */
		@SuppressWarnings("SameParameterValue")
		static String readInputSyntaxAware(StringBuilder input, ParsePosition position, String delimiterRegexp, StringBuilder result)
		{
			var matcher = Pattern.compile(combineRE("\\(|\"", delimiterRegexp)).matcher(input);
			while (true)
			{
				String delimiter = null;
				if (matcher.find(position.getIndex()))
				{
					result.append(input, position.getIndex(), matcher.start());
					var start = matcher.start();
					position.setIndex(matcher.end());
					delimiter = matcher.group();
					switch (delimiter)
					{
						case "(" -> {
							result.append(delimiter);
							delimiter = readInputSyntaxAware(input, position, "\\)", result);
							if (delimiter == null)
							{
								throw new RuntimeException("Unclosed parenthesized construct: " + input.substring(start));
							}
							result.append(delimiter);
							continue;
						}
						case "\"" -> {
							result.append(input, start, findClosingQuote(input, position, start));
							continue;
						}
					}
					// if we are here, it was delimiterRegexp that matched -> done
				}
				else
				{
					result.append(input.substring(position.getIndex()));
					position.setIndex(input.length());
				}
				// text complete
				return delimiter;
			}
		}

		/**
		 * Read the left hand side of an assignment from input if present
		 *
		 * @param input        the input to read from starting at <tt>position</tt>
		 * @param position     the input position at which to start reading, will be updated
		 *                     to point to the position after the equals sign
		 * @param killPatterns regular expression identifying disallowed text
		 * @return parsed left hand side of assignment if present, otherwise <tt>null</tt>
		 */
		@SuppressWarnings("ConditionalBreakInInfiniteLoop")
		static List<String> readAssignmentKey(StringBuilder input, ParsePosition position, String killPatterns)
		{
			var start = position.getIndex();
			var result = new ArrayList<String>();
			var commaPattern = ",".matches(killPatterns) ? "\\\\," : ",";
			String delimiter;
			var breakPatterns = combineRE(commaPattern, combineRE("=", killPatterns));
			while (true)
			{
				var buffer = new StringBuilder();
				delimiter = readInput(input, position, breakPatterns, true, buffer);
				result.add(buffer.toString());
				if ((delimiter == null) || !delimiter.matches(commaPattern))
				{
					break;
				}

			}
			if (!"=".equals(delimiter))
			{
				position.setIndex(start);
				result = null;
			}
			return result;
		}

		/**
		 * Scan input for the closing quote of the string literal into which position points,
		 * skipping any escaped quotes. Advance position behind it and return this index.
		 * The openingQuotePosition is needed for error reporting only.
		 */
		@SuppressWarnings("ConditionalBreakInInfiniteLoop")
		static int findClosingQuote(StringBuilder input, ParsePosition position, int openingQuotePosition)
		{
			var stringLiteralScanner = Pattern.compile("\\\\.|\"").matcher(input);
			while (true)
			{
				if (!stringLiteralScanner.find(position.getIndex()))
				{
					throw new RuntimeException("Unclosed string literal: " + input.substring(openingQuotePosition));
				}
				position.setIndex(stringLiteralScanner.end());
				if ("\"".equals(stringLiteralScanner.group()))
				{
					break;
				}
			}
			return position.getIndex();
		}

		/**
		 * Assure the provided regular expression or EOI matches at the current input position
		 * and update the input position
		 *
		 * @param input           the input to read from starting at <tt>position</tt>
		 * @param start           the input position at which the construct being parsed starts
		 * @param position        the input position at which to start reading, will be updated
		 *                        to point to the position after the matched delimiter or after EOI
		 * @param delimiterRegexp the regular expression being expected to match at the
		 *                        current input position
		 * @return the text matched by the delimiter expression or <tt>null</tt> at EOI
		 */
		static String close(StringBuilder input, int start, ParsePosition position, String delimiterRegexp)
		{
			var buffer = new StringBuilder();
			var location = position.getIndex();
			var delimiter = readInput(input, position, delimiterRegexp, true, buffer);
			if (!buffer.isEmpty())
			{
				var detail = ((location > 20) && (start != 0)) ? String.format(" following `%s`", input.substring(start, location)) : "";
				throw new RuntimeException(String.format("Garbage at position %d of `%s`%s: `%s`", location, input, detail, buffer));
			}
			return delimiter;
		}

		/**
		 * Throw exception if delimiter is not an opening parenthesis
		 */
		static void checkOpeningParenthesis(String name, String delimiter)
		{
			if (!"(".equals(delimiter))
			{
				throw new RuntimeException(String.format("%s instruction requires parameters", name));
			}
		}

		/**
		 * Throw exception if delimiter is not a closing parenthesis
		 */
		static void checkClosingParenthesis(String name, String delimiter)
		{
			if (!")".equals(delimiter))
			{
				throw new RuntimeException(String.format("%s instruction not properly terminated", name));
			}
		}

		/**
		 * Combine a regular expression with a second one if given
		 */
		static String combineRE(String mandatory, String optional)
		{
			return (optional == null) ? mandatory : String.format("%s|%s", mandatory, optional);
		}

		/**
		 * Treat the given object as Iterable using either cast, Map.entrySet() or Collections.singletonList()
		 */
		@SuppressWarnings("unchecked")
		public static Iterable<Object> asIterable(Object source)
		{
			var target = (source == null) ? null : unwrap(source);
			//@formatter:off
			@SuppressWarnings("NestedConditionalExpression")
			var result = (Iterable<Object>) (
					(target instanceof Iterable) ? target :
					(target instanceof Map) ? ((Map<Object, Object>) target).entrySet() :
					(target != null) ? Collections.singletonList(source) : // Important: Preserve the parent chain!
					Collections.emptyList());
			//@formatter:on
			return result;
		}

		private String expression;
		private Object key;

		private void setExpression(String expression)
		{
			this.expression = expression;
		}

		/**
		 * Return the expression the accessor was compiled from (only for top level accessors).
		 *
		 * @return OrionQL source code for top level accessors, otherwise <tt>null</tt>
		 */
		public String getExpression()
		{
			return expression;
		}

		/**
		 * <p>Associate a key with the accessor. This can be an arbitrary object that
		 * application code needs to have associated with an accessor, and it is not
		 * interpreted by OrionQL in any way.</p>
		 *
		 * <p>For top level accessors, when compiling <i>assignment style</i> OrionQL
		 * code, the resulting accessor's key is set automatically by the compilation
		 * process to the assignment's <i>target</i>.</p>
		 *
		 * @param key the Object to associate with the accessor
		 * @return this
		 */
		public Accessor setKey(Object key)
		{
			this.key = key;
			return this;
		}

		/**
		 * Return the key associated with the accessor
		 *
		 * @return key
		 */
		public Object getKey()
		{
			return key;
		}

		/**
		 * Check if a key has been associated with the accessor
		 *
		 * @return true if a key was associated with the accessor
		 */
		public boolean hasKey()
		{
			return (key != null);
		}

		/**
		 * Return the name of the accessor. This is either the string representation of
		 * the accessor's key if one was set, or the expression from which the accessor
		 * was compiled (for top level accessors only).
		 *
		 * @return name of the accessor or <tt>null</tt> if neither key nor expression was set
		 */
		public String getName()
		{
			return (key != null) ? key.toString() : expression;
		}

		/**
		 * Retrieve from the <tt>source</tt> object what the accessor stands for. Implemented
		 * in the subclasses. This is the internal expression composition interface, so the
		 * result can be a Proxy.
		 *
		 * @param source  the result of evaluating the expression so far and thus the object
		 *                to access data from by this accessor
		 * @param context the expression evaluation context, which is the proxy from which
		 *                the expression evaluation started
		 * @return the access result
		 */
		abstract Object access(Object source, Proxy<?> context);

		public <T, V> V access(Proxy<T> source)
		{
			// client code will always get the real objects
			return unwrap(access(source, source));
		}
	}

	/**
	 * Accessors of this type represent an expression made up of multiple
	 * OrionQL expressions connected by operators.
	 */
	private static class CompositeAccessor extends Accessor
	{
		private final List<Accessor> accessors;
		private final List<String> operators;

		private CompositeAccessor(List<Accessor> accessors, List<String> operators)
		{
			this.accessors = accessors;
			this.operators = operators;
		}

		/**
		 * Documentation see {@link Accessor#parse(StringBuilder, ParsePosition, String, Map, List)}
		 */
		static String parse(StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			return parse(expression, position, endMarkers, macroLibrary, result, 0);
		}

		private static final String[] compositeExpressionOperators = new String[]{ "\\|", "==|!=|>=?|<=?", "\\+|\\-", "\\*|/" };
		static String parse(StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result, int level)
		{
			var operatorPattern = compositeExpressionOperators[level];
			var delimiterPatterns = combineRE(operatorPattern, endMarkers);
			List<Accessor> accessors = new ArrayList<>();
			List<String> operators = new ArrayList<>();
			String delimiter;
			var start = position.getIndex();
			while (true)
			{
				if (expandMacro(expression, position, macroLibrary))
				{
					continue;
				}
				if (level + 1 < compositeExpressionOperators.length)
				{
					delimiter = parse(expression, position, delimiterPatterns, macroLibrary, accessors, level + 1);
				}
				else
				{
					delimiter = AccessorChain.parse(expression, position, delimiterPatterns, macroLibrary, accessors);
				}
				if ((delimiter == null) || ((endMarkers != null) && delimiter.matches(endMarkers)))
				{
					break;
				}
				operators.add(delimiter);
			}
			var accessor = (accessors.size() == 1) ? accessors.get(0) : new CompositeAccessor(accessors, operators);
			accessor.setExpression(expression.substring(start, position.getIndex() - ((delimiter == null) ? 0 : delimiter.length())));
			result.add(accessor);
			return delimiter;
		}

		/**
		 * Read multiple expression specifications from <tt>expression</tt>, accepting assignment syntax
		 * as well, and append the compiled accessors to <tt>result</tt>. Upon entry, <tt>position</tt>
		 * points to the first character of the first expression specification. Upon exit, <tt>position</tt>
		 * points behind the end marker found or behind the end of input. The end marker is returned.
		 *
		 * @param expression          the expression under parsing
		 * @param position            the current parse position in it, will be updated
		 * @param delimiterPattern    the regular expression identifying the delimiter between expressions
		 * @param endMarkers          the regular expression signaling the end of the construct to parse
		 * @param useAssignmentSyntax if null, assignment syntax is accepted but not required, otherwise specifies if assignment syntax is used or not
		 * @param result              the accessors resulting from the parse will be added to this list
		 * @return the delimiter matched by <tt>endMarkers</tt> or <tt>null</tt> when EOI was reached
		 */
		static String parseAll(StringBuilder expression, ParsePosition position, String delimiterPattern, String endMarkers, Boolean useAssignmentSyntax, Map<String, String> macroLibrary, List<Accessor> result)
		{
			var endPatterns = combineRE(delimiterPattern, endMarkers);
			String delimiter;
			while (true)
			{
				if (expandMacro(expression, position, macroLibrary))
				{
					continue;
				}
				var start = position.getIndex();
				var key = ((useAssignmentSyntax == null) || useAssignmentSyntax) ? readAssignmentKey(expression, position, combineRE(":", endPatterns)) : null;
				if (Boolean.TRUE.equals(useAssignmentSyntax) && (key == null))
				{
					throw new RuntimeException("Missing assignment before expression: " + expression.substring(start));
				}
				delimiter = parse(expression, position, endPatterns, macroLibrary, result);
				if (((useAssignmentSyntax == null) || useAssignmentSyntax) && (start == position.getIndex() - ((delimiter == null) ? 0 : delimiter.length())))
				{
					throw new RuntimeException(String.format("Empty expression definition at position %d: %s", start, expression.substring(start)));
				}
				if (key != null)
				{
					result.get(result.size() - 1).setKey((key.size() == 1) ? key.get(0) : key);
				}
				if ((delimiter == null) || ((endMarkers != null) && delimiter.matches(endMarkers)))
				{
					break;
				}
			}
			return delimiter;
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			var values = accessors.iterator();
			var operations = operators.iterator();
			var current = values.next().access(source, context);
			// note that we always have operators of equal precedence here
			// and that we only support left-associative operators, so we
			// can apply them from left to right
			while (operations.hasNext())
			{
				var operation = operations.next();
				if ("|".equals(operation))
				{
					if (unwrap(current) != null)
					{
						return current;
					}
					current = values.next().access(source, context);
				}
				else
				{
					var isEqualityCheck = ("==".equals(operation) || "!=".equals(operation));
					var left = coerceTypes(unwrap(current));
					if ((left == null) && !isEqualityCheck)
					{
						return null;
					}
					var right = coerceTypes(unwrap(values.next().access(source, context)));
					if ((right == null) && !isEqualityCheck)
					{
						return null;
					}
					current = switch (operation)
					{
						case "+" -> add(left, right);
						case "-" -> subtract(left, right);
						case "*" -> multiply(left, right);
						case "/" -> divide(left, right);
						case "==" -> equals(left, right, false);
						case "!=" -> equals(left, right, true);
						case "<" -> compareLT(left, right, false);
						case ">" -> compareLT(right, left, false);
						case "<=" -> compareLT(right, left, true);
						case ">=" -> compareLT(left, right, true);
						default -> throw new RuntimeException("Unknown operator: " + operation);
					};
				}
			}
			return current;
		}

		/**
		 * Perform implicit number parsing and reduce type diversity by converting
		 * certain types into more general types (currently this concerns only float,
		 * as converting int to long would in most cases prevent using the computation
		 * result - we currently do not allow using long values in computations).
		 * @param operand input value
		 * @return input value, possibly converted to more general type
		 */
		private static Object coerceTypes(Object operand)
		{
			if (operand instanceof String)
			{
				// note that this prevents to use multiplication for repeating
				// strings that are valid numbers
				String operandString = (String) operand;
				if (operandString.matches("[-+]?\\d+"))
				{
					var value = Long.parseLong(operandString);
					if ((value < Integer.MAX_VALUE) && (value > Integer.MIN_VALUE))
					{
						return (int) value;
					}
					return value;
				}
				if (operandString.matches("[-+]?\\d+[Ll]"))
				{
					return Long.parseLong(operandString.substring(0, operandString.length() - 1));
				}
				if (operandString.matches("[-+]?\\d*\\.\\d*") && operandString.matches(".*\\d.*"))
				{
					return Double.parseDouble(operandString);
				}
				return operand;
			}
			if (operand instanceof Float)
			{
				return (double) (Float) operand;
			}
			return operand;
		}

		private static Object add(Object left, Object right)
		{
			if (left instanceof Integer)
			{
				if (right instanceof Integer)
				{
					return (Integer) left + (Integer) right;
				}
				if (right instanceof Double)
				{
					return (Integer) left + (Double) right;
				}
			}
			if (left instanceof Double)
			{
				if (right instanceof Integer)
				{
					return (Double) left + (Integer) right;
				}
				if (right instanceof Double)
				{
					return (Double) left + (Double) right;
				}
			}
			if (left instanceof Date)
			{
				if (right instanceof Integer)
				{
					return new Date(((Date) left).getTime() + ((Integer) right) * 1000);
				}
				if (right instanceof Long)
				{
					return new Date(((Date) left).getTime() + (Long) right);
				}
			}
			throw new RuntimeException(String.format("Operation not defined for operand types: %s + %s", left.getClass(), right.getClass()));
		}

		private static Object subtract(Object left, Object right)
		{
			if (left instanceof Integer)
			{
				if (right instanceof Integer)
				{
					return (Integer) left - (Integer) right;
				}
				if (right instanceof Double)
				{
					return (Integer) left - (Double) right;
				}
			}
			if (left instanceof Double)
			{
				if (right instanceof Integer)
				{
					return (Double) left - (Integer) right;
				}
				if (right instanceof Double)
				{
					return (Double) left - (Double) right;
				}
			}
			if (left instanceof Date)
			{
				if (right instanceof Date)
				{
					long difference = (((Date) left).getTime() - ((Date) right).getTime()) / 1000L;
					if ((difference > Integer.MAX_VALUE) || (difference < Integer.MIN_VALUE))
					{
						throw new RuntimeException(String.format("Timespan out of range: %d years", difference / (365 * 86400)));
					}
					return (int) difference;
				}
				if (right instanceof Integer)
				{
					return new Date(((Date) left).getTime() - ((Integer) right) * 1000);
				}
				if (right instanceof Long)
				{
					return new Date(((Date) left).getTime() - (Long) right);
				}
			}
			throw new RuntimeException(String.format("Operation not defined for operand types: %s - %s", left.getClass(), right.getClass()));
		}

		private static Object multiply(Object left, Object right)
		{
			if (left instanceof Integer)
			{
				if (right instanceof Integer)
				{
					return (Integer) left * (Integer) right;
				}
				if (right instanceof Double)
				{
					return (Integer) left * (Double) right;
				}
				if (right instanceof String)
				{
					return ((String) right).repeat((Integer) left);
				}
			}
			if (left instanceof Double)
			{
				if (right instanceof Integer)
				{
					return (Double) left * (Integer) right;
				}
				if (right instanceof Double)
				{
					return (Double) left * (Double) right;
				}
				if (right instanceof String)
				{
					return ((String) right).repeat(((Double) left).intValue());
				}
			}
			throw new RuntimeException(String.format("Operation not defined for operand types: %s * %s", left.getClass(), right.getClass()));
		}

		private static Object divide(Object left, Object right)
		{
			if (left instanceof Integer)
			{
				if (right instanceof Integer)
				{
					return (Integer) left / (Integer) right;
				}
				if (right instanceof Double)
				{
					return (Integer) left / (Double) right;
				}
			}
			if (left instanceof Double)
			{
				if (right instanceof Integer)
				{
					return (Double) left / (Integer) right;
				}
				if (right instanceof Double)
				{
					return (Double) left / (Double) right;
				}
			}
			throw new RuntimeException(String.format("Operation not defined for operand types: %s / %s", left.getClass(), right.getClass()));
		}

		private static Object equals(Object left, Object right, boolean isInverted)
		{
			if ((left == null) && (right == null))
			{
				return !isInverted;
			}
			if ((left == null) || (right == null))
			{
				return isInverted;
			}
			// make sure we can match objects like Enums with Strings
			if ((left instanceof String) || (right instanceof String))
			{
				return left.toString().equals(right.toString()) != isInverted;
			}
			return left.equals(right) != isInverted;
		}

		@SuppressWarnings("unchecked")
		private static Object compareLT(Object left, Object right, boolean isInverted)
		{
			var leftComparable = (Comparable<Object>) left;
			var rightComparable = (Comparable<Object>) right;
			return (leftComparable.compareTo(rightComparable) < 0) != isInverted;
		}
	}

	/**
	 * Accessors of this type represent a sequence of OrionQL Accessors that
	 * are applied one after another, each one operating on the result of the preceding
	 * one. They represent the most common type of OrionQL expressions.
	 */
	private static class AccessorChain extends Accessor
	{
		private final List<Accessor> accessors;

		private AccessorChain(List<Accessor> accessors)
		{
			this.accessors = accessors;
		}

		/**
		 * Documentation see {@link Accessor#parse(StringBuilder, ParsePosition, String, Map, List)}
		 */
		static String parse(StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			var delimiterPatterns = combineRE("\\.|:", endMarkers);
			List<Accessor> accessors = new ArrayList<>();
			var delimiter = "";
			while (delimiter != null)
			{
				switch (delimiter)
				{
					case "":
						// at chain start, literals are accepted, ...
						delimiter = LiteralAccessor.parse(expression, position, delimiterPatterns, macroLibrary, accessors);
						if (!accessors.isEmpty())
						{
							continue;
						}
						// ... otherwise the implied accessor is an attribute accessor or parenthesized expression (fall through)
					case ".":
						if (expandMacro(expression, position, macroLibrary))
						{
							continue;
						}
						if (startsWith(expression, position,"("))
						{
							var start = position.getIndex();
							position.setIndex(start + 1);
							delimiter = CompositeAccessor.parse(expression, position, "\\)", macroLibrary, accessors);
							if (delimiter == null)
							{
								throw new RuntimeException("Parenthesized expression not properly closed: " + expression.substring(start));
							}
							delimiter = close(expression, start, position, delimiterPatterns);
						}
						else
						{
							delimiter = AttributeAccessor.parse(expression, position, delimiterPatterns, macroLibrary, accessors);
						}
						continue;
					case ":":
						delimiter = ProcessingInstruction.parse(expression, position, delimiterPatterns, macroLibrary, accessors);
						continue;
				}
				// if none of the above, we reached EOI
				break;
			}
			result.add((accessors.size() == 1) ? accessors.get(0) : new AccessorChain(accessors));
			return delimiter;
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			var current = source;
			for (Accessor accessor : accessors)
			{
				current = accessor.access(current, context);
				if (unwrap(current) == null)
				{
					break;
				}
			}
			return current;
		}
	}

	/**
	 * Accessors of this type represent a literal value.
	 */
	private static class LiteralAccessor extends Accessor
	{
		private final Object value;

		private LiteralAccessor(Object value)
		{
			this.value = value;
		}

		/**
		 * Documentation see {@link Accessor#parse(StringBuilder, ParsePosition, String, Map, List)}
		 */
		@SuppressWarnings("unused")
		static String parse(StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			var buffer = new StringBuilder();
			var start = position.getIndex();
			var delimiter = readInput(expression, position, combineRE("\"", endMarkers), true, buffer);
			if (buffer.isEmpty() && (("-".equals(delimiter) || "+".equals(delimiter))))
			{
				// leading sign was interpreted as operator following an empty expression
				buffer.append(delimiter);
				delimiter = readInput(expression, position, endMarkers, true, buffer);
			}
			if (buffer.isEmpty() && "\"".equals(delimiter))
			{
				if (delimiter.matches(endMarkers))
				{
					position.setIndex(start);
				}
				else
				{
					delimiter = readInput(expression, position, "\"", false, buffer);
					if (delimiter == null)
					{
						throw new RuntimeException("String literal not properly terminated: " + expression.substring(start));
					}
					delimiter = close(expression, start, position, endMarkers);
					result.add(new LiteralAccessor(buffer.toString()));
				}
			}
			else
			{
				var value = buffer.toString();
				if ("null".equals(value))
				{
					result.add(new LiteralAccessor(null));
				}
				else if ("true".equals(value))
				{
					result.add(new LiteralAccessor(true));
				}
				else if ("false".equals(value))
				{
					result.add(new LiteralAccessor(false));
				}
				else if (value.matches("[-+]?\\d+"))
				{
					if (".".equals(delimiter))
					{
						// a number literal cannot be followed by a navigation, so
						// obviously we have a float here (note that we don't allow
						// attribute names consisting of only digits); incorrect
						// syntax will cause a NumberFormatException
						buffer.append(delimiter);
						delimiter = readInput(expression, position, endMarkers, false, buffer);
						value = buffer.toString();
						result.add(new LiteralAccessor(Double.parseDouble(value)));
					}
					else
					{
						result.add(new LiteralAccessor(Integer.parseInt(value)));
					}
				}
				else if (value.matches("[-+]?\\d+[Ll]"))
				{
					result.add(new LiteralAccessor(Long.parseLong(value.substring(0, value.length() - 1))));
				}
				else if (value.matches("(\\d+[dhms])+"))
				{
					result.add(new LiteralAccessor(parseTimeRange(value)));
				}
				else
				{
					position.setIndex(start);
				}
			}
			return delimiter;
		}

		//@formatter:off
		private static final Map<String, Long> timescales = Literals.asMap(
				"d", 86400000L).add(
				"h", 3600000L).add(
				"m", 60000L).add(
				"s", 1000L);
		//@formatter:on
		private static long parseTimeRange(String value)
		{
			var result = 0L;
			var matcher = Pattern.compile("(\\d+)([dhms])").matcher(value);
			while (matcher.find())
			{
				result += Long.parseLong(matcher.group(1)) * timescales.get(matcher.group(2));
			}
			return result;
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			return value;
		}
	}

	/**
	 * Accessors of this type represent an object attribute. They are the most
	 * elementary type of accessors.
	 */
	private static class AttributeAccessor extends Accessor
	{
		private final String attributeName;
		private final Accessor parameterAccessor;
		private final boolean isTolerant;

		private AttributeAccessor(String attributeName, Accessor parameterAccessor, boolean isTolerant)
		{
			this.attributeName = attributeName;
			this.parameterAccessor = parameterAccessor;
			this.isTolerant = isTolerant;
		}

		/**
		 * Documentation see {@link Accessor#parse(StringBuilder, ParsePosition, String, Map, List)}
		 */
		static String parse(StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			var delimiterPatterns = combineRE("\\(", endMarkers);
			var buffer = new StringBuilder();
			var start = position.getIndex();
			var isTolerant = false;
			var delimiter = readInput(expression, position, combineRE("\\?", delimiterPatterns), true, buffer);
			if ("?".equals(delimiter))
			{
				isTolerant = true;
				delimiter = close(expression, start, position, delimiterPatterns);
			}
			if (!buffer.isEmpty())
			{
				var name = buffer.toString();
				if ("(".equals(delimiter))
				{
					var accessors = new ArrayList<Accessor>();
					checkClosingParenthesis(name, CompositeAccessor.parse(expression, position, "\\)", macroLibrary, accessors));
					result.add(new AttributeAccessor(name, accessors.get(0), isTolerant));
					return close(expression, start, position, endMarkers);
				}
				else
				{
					result.add(new AttributeAccessor(name, null, isTolerant));
				}
			}
			return delimiter;
		}

		@SuppressWarnings("unchecked")
		@Override
		Object access(Object source, Proxy<?> context)
		{
			if ("@".equals(attributeName))
			{
				return context;
			}
			if ("@@".equals(attributeName))
			{
				return unwrap(source);
			}
			if (attributeName.startsWith("^"))
			{
				var proxy = (source instanceof Proxy) ? (Proxy<?>) source : context;
				var parent = proxy.parent;
				if ((parent == null) || (parent.payload == null))
				{
					if (isTolerant)
					{
						return null;
					}
					throw new RuntimeException("No parent to navigate to from " + source);
				}
				if ("^".equals(attributeName))
				{
					return parent;
				}
				else
				{
					var parser = Pattern.compile("(\\^\\^?)(\\w*)").matcher(attributeName);
					if (!parser.matches())
					{
						throw new RuntimeException("Invalid navigation: " + attributeName);
					}
					var findTop = (parser.group(1).length() == 2);
					var className = parser.group(2);
					Proxy<?> candidate = null;
					while ((parent != null) && (parent.payload != null))
					{
						if (className.isEmpty() || parent.payload.getClass().getSimpleName().equals(className))
						{
							candidate = parent;
						}
						if ((candidate != null) && !findTop)
						{
							return candidate;
						}
						parent = parent.parent;
					}
					if (candidate != null)
					{
						return candidate;
					}
					// note that if we are here, selection was by className
					if (isTolerant)
					{
						return null;
					}
					throw new RuntimeException(String.format("No parent object with class %s reachable from %s", className, source));
				}
			}
			if (source instanceof Proxy)
			{
				var proxy = (Proxy<Object>) source;
				if (proxy.overrides.containsKey(attributeName))
				{
					return proxy.overrides.get(attributeName);
				}
				source = proxy.payload;
			}
			if (source == null)
			{
				return null;
			}
			if (source instanceof Map)
			{
				var map = (Map<String, Object>) source;
				return map.get(attributeName);
			}
			try
			{
				Method getter;
				var classDescription = ClassDescription.get(source.getClass());
				if (parameterAccessor != null)
				{
					var parameter = unwrap(parameterAccessor.access(context));
					if (parameter == null)
					{
						throw new RuntimeException(String.format("Parameter is null accessing %s(%s)", attributeName, parameterAccessor.getExpression()));
					}
					getter = classDescription.getParameterizedGetter(attributeName, parameter.getClass());
					if (getter != null)
					{
						return getter.invoke(source, parameter);
					}
					else if (isTolerant)
					{
						return null;
					}
					throw new RuntimeException(String.format("No such attribute in class %s: %s(%s)", source.getClass().getCanonicalName(), attributeName, parameter.getClass().getCanonicalName()));
				}
				else
				{
					getter = classDescription.getSimpleGetter(attributeName);
					if (getter != null)
					{
						return getter.invoke(source);
					}
					if (context.resolver != null)
					{
						getter = classDescription.getResolvingGetter(attributeName);
						if (getter != null)
						{
							return getter.invoke(source, context.resolver);
						}
					}
					getter = classDescription.getAttributesGetter();
					if (getter != null)
					{
						var attributes = (Map<String, Object>) getter.invoke(source);
						return (attributes == null) ? null : attributes.get(attributeName);
					}
					else if (isTolerant)
					{
						return null;
					}
					throw new RuntimeException(String.format("No such attribute in class %s: %s", source.getClass().getCanonicalName(), attributeName));
				}
			}
			catch (RuntimeException e)
			{
				throw e;
			}
			catch (Exception e)
			{
				throw new RuntimeException(e);
			}
		}
	}

	/**
	 * This is the common base of all processing instructions. Accessors of this
	 * type perform various types of processings on their input values and are
	 * implemented in the subclasses.
	 */
	private abstract static class ProcessingInstruction extends Accessor
	{
		/**
		 * Documentation see {@link Accessor#parse(StringBuilder, ParsePosition, String, Map, List)}
		 */
		static String parse(StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			var delimiterPatterns = combineRE("\\(", endMarkers);
			var buffer = new StringBuilder();
			var start = position.getIndex() - 1;
			var delimiter = readInput(expression, position, delimiterPatterns, true, buffer);
			var name = buffer.toString();
			switch (name)
			{
				case "join":
					return JoinInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				case "format", "html":
					checkOpeningParenthesis(name, delimiter);
					checkClosingParenthesis(name, TemplateSubstitution.parse(expression, position, "\\)", macroLibrary, result));
					if ("html".equals(name) && (result.get(result.size() - 1) instanceof TemplateSubstitution))
					{
						((TemplateSubstitution) result.get(result.size() - 1)).setResultType(TemplateSubstitution.ResultType.HTML);
					}
					return close(expression, start, position, endMarkers);
				case "link":
					return LinkInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				case "heat":
					return HeatInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				case "map":
					return MapInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				case "sum", "avg", "max", "min":
					return AggregationInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				case "sort":
					return SortInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				case "count", "group", "index":
					return IndexingInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				case "get":
					return IndexAccessInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				case "union":
					return UnionInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				case "construct":
					return ConstructInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				case "table", "htable":
					return TableInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				case "grid":
					return GridInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				case "barchart", "linechart":
					return ChartInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				case "flatten":
					return FlatteningInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				case "match", "extract":
					return ExtractInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				case "replace":
					return ReplaceInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				case "env":
					return EnvironmentInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				case "cache", "recache", "swapcache":
					return CachingInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				case "select":
					return SelectInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				case "switch":
					return SwitchInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				case "inspect":
					return InspectInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				case "pprint":
					return PrettyPrintMapConversion.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				case "toXml":
					result.add(new PrintToXmlConversion());
					return delimiter;
				case "parseXml":
					result.add(new ParseXmlConversion());
					return delimiter;
				case "upcase", "downcase":
					result.add(new StringCaseConversion(name));
					return delimiter;
				case "size":
					result.add(new SizeInstruction());
					return delimiter;
				case "date", "now":
					return DateInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
				default:
					return LookupInstruction.finalize(name, delimiter, expression, position, endMarkers, macroLibrary, result);
			}
		}
	}

	/**
	 * This processing instruction can be invoked on a Collection and concatenates
	 * the String representations of its elements into a String using either newline or
	 * an explicitly provided glue String.
	 */
	private static class JoinInstruction extends ProcessingInstruction
	{
		private final String glue;

		private JoinInstruction(String glue)
		{
			this.glue = glue;
		}

		@SuppressWarnings({"MethodWithTooManyParameters", "unused"})
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			if ("(".equals(delimiter))
			{
				var buffer = new StringBuilder();
				var start = position.getIndex() - name.length() - 2;
				checkClosingParenthesis(name, readInput(expression, position, "\\)", false, buffer));
				delimiter = close(expression, start, position, endMarkers);
				result.add(new JoinInstruction(buffer.toString()));
			}
			else
			{
				result.add(new JoinInstruction("\n"));
			}
			return delimiter;
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			var result = new StringBuilder();
			var input = asIterable(source).iterator();
			Boolean isHtml = null;
			while (input.hasNext()) {
				var element = unwrap(input.next());
				if (element != null)
				{
					if (isHtml == null)
					{
						isHtml = (element instanceof OrionQL.HtmlContainer);
					}
					result.append(
							(isHtml && !(element instanceof OrionQL.HtmlContainer)) ?
									StringEscapeUtils.escapeHtml(element.toString()) :
									element.toString()
					);
				}
				if (input.hasNext())
				{
					result.append(glue);
				}
			}
			return (isHtml == Boolean.TRUE) ? new OrionQL.HtmlContainer(result.toString(), null) : result.toString();
		}
	}

	/**
	 * This processing instruction can be invoked on a collection and will return
	 * a new collection (actually, a List) which will contain for every element of the
	 * source collection the result of applying an OrionQL expression to this
	 * element.
	 */
	private static class MapInstruction extends ProcessingInstruction
	{
		private final Accessor accessor;

		private MapInstruction(Accessor accessor)
		{
			this.accessor = accessor;
		}

		@SuppressWarnings("MethodWithTooManyParameters")
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			checkOpeningParenthesis(name, delimiter);
			var accessors = new ArrayList<Accessor>();
			var start = position.getIndex() - name.length() - 2;
			checkClosingParenthesis(name, CompositeAccessor.parse(expression, position, "\\)", macroLibrary, accessors));
			result.add(new MapInstruction(accessors.get(0)));
			return close(expression, start, position, endMarkers);
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			var result = new ArrayList<>();
			for (var element: asIterable(source))
			{
				// the element is the new context for the expression evaluation;
				// do unwrap the result to be consistent with sort, index, select
				result.add(accessor.access(context.derive(element)));
			}
			return result;
		}
	}

	/**
	 * This processing instruction can be invoked on a collection and will return
	 * the result of applying the named aggregation function.
	 */
	private static class AggregationInstruction extends ProcessingInstruction
	{
		private final String name;
		private final Accessor accessor;
		private final BiFunction<Object, Object, Object> worker;

		@SuppressWarnings("unchecked")
		private AggregationInstruction(String name, Accessor accessor)
		{
			this.name = name;
			worker = switch (name)
			{
				case "sum", "avg" -> CompositeAccessor::add;
				case "max" -> (current, next) -> (((Comparable<Object>) current).compareTo(next) < 0) ? next: current;
				case "min" -> (current, next) -> (((Comparable<Object>) current).compareTo(next) > 0) ? next: current;
				default -> throw new RuntimeException("Unknown aggregation instruction: " + name);
			};
			this.accessor = accessor;
		}

		@SuppressWarnings("MethodWithTooManyParameters")
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			if ("(".equals(delimiter))
			{
				var accessors = new ArrayList<Accessor>();
				var start = position.getIndex() - name.length() - 2;
				checkClosingParenthesis(name, CompositeAccessor.parse(expression, position, "\\)", macroLibrary, accessors));
				delimiter = close(expression, start, position, endMarkers);
				result.add(new AggregationInstruction(name, accessors.get(0)));
			}
			else
			{
				result.add(new AggregationInstruction(name, new AccessorChain(Collections.emptyList())));
			}
			return delimiter;
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			var input = asIterable(source).iterator();
			Object current = null;
			while (input.hasNext())
			{
				current = accessor.access(context.derive(input.next()));
				if (current != null)
				{
					break;
				}
			}
			if (!input.hasNext())
			{
				return current;
			}
			var count = 1;
			while (input.hasNext())
			{
				var next = accessor.access(context.derive(input.next()));
				if (next != null)
				{
					current = worker.apply(current, next);
					++count;
				}
			}
			if ("avg".equals(name))
			{
				return CompositeAccessor.divide(current, (double) count);
			}
			else
			{
				return current;
			}
		}
	}

	/**
	 * This processing instruction can be invoked on a collection and will return
	 * a new collection (actually, a List) containing the elements of the source
	 * collection sorted by one or several criteria that are given by OrionQL
	 * expressions. Without an expression, the elements are sorted directly. The sort
	 * is stable.
	 */
	private static class SortInstruction extends ProcessingInstruction
	{
		private final IndexedData<Object, Object> indexer;
		private final List<Boolean> ascending;

		private SortInstruction(List<Accessor> accessors, List<Boolean> ascending)
		{
			indexer = new IndexedData<>(accessors, null);
			this.ascending = ascending;
		}

		@SuppressWarnings("MethodWithTooManyParameters")
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			List<Accessor> accessors = new ArrayList<>();
			List<Boolean> ascending = new ArrayList<>();
			if ("(".equals(delimiter))
			{
				var start = position.getIndex() - name.length() - 2;
				delimiter = ",";
				while (",".equals(delimiter))
				{
					var sortAscending = true;
					delimiter = CompositeAccessor.parse(expression, position, " |,|\\)", macroLibrary, accessors);
					if (" ".equals(delimiter))
					{
						var buffer = new StringBuilder();
						delimiter = readInput(expression, position, ",|\\)", false, buffer);
						if ("desc".contentEquals(buffer))
						{
							sortAscending = false;
						}
					}
					ascending.add(sortAscending);
				}
				checkClosingParenthesis(name, delimiter);
				delimiter = close(expression, start, position, endMarkers);
			}
			else
			{
				accessors.add(new AccessorChain(Collections.emptyList()));
				ascending.add(true);
			}
			result.add(new SortInstruction(accessors, ascending));
			return delimiter;
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			var index = new HashMap<>();
			indexer.set(index);
			var unwrap = true;
			for (var element : asIterable(source))
			{
				if (element instanceof Proxy)
				{
					unwrap = false;
				}
				indexer.add(context.derive(element));
			}
			var result = new ArrayList<>();
			flatten(index, 0, result, unwrap);
			return result;
		}

		@SuppressWarnings("unchecked")
		private void flatten(Map<Object, Object> current, int level, List<Object> result, boolean unwrap)
		{
			var keySet = new HashSet<>(current.keySet());
			var keys = new ArrayList<>();
			if (keySet.contains(null))
			{
				keySet.remove(null);
				keys.add(null);
			}
			var sorted = new ArrayList<>(new TreeSet<>(keySet));
			if (!ascending.get(level))
			{
				Collections.reverse(sorted);
			}
			keys.addAll(sorted);
			var nextLevel = level + 1;
			for (var key: keys)
			{
				var bucket = current.get(key);
				if (nextLevel >= ascending.size())
				{
					if (unwrap)
					{
						for (var element: (Collection<Proxy<?>>) bucket)
						{
							result.add(element.unwrap());
						}
					}
					else
					{
						result.addAll((Collection<Object>) bucket);
					}
				}
				else
				{
					flatten((Map<Object, Object>) bucket, nextLevel, result, unwrap);
				}
			}
		}
	}

	/**
	 * This processing instruction can be invoked on a Collection and performs
	 * the requested aggregation operation, returning a potentially multilevel.
	 */
	private static class IndexingInstruction extends ProcessingInstruction
	{
		private final IndexedData<Object, Object> indexer;
		private final Supplier<Map<Object, Object>> mapConstructor;
		private final Consumer<Proxy<Object>> worker;

		private IndexingInstruction(String name, List<Accessor> accessors, Supplier<Map<Object, Object>> mapConstructor, Accessor mapper)
		{
			this.mapConstructor = mapConstructor;
			indexer = new IndexedData<>(accessors, mapper);
			worker = switch (name)
			{
				case "count" -> indexer::count;
				case "group" -> indexer::add;
				case "index" -> o ->
				{
					if (indexer.put(o) != null)
					{
						throw new RuntimeException("Key collision: " + o);
					}
				};
				default -> throw new RuntimeException("No such aggregation: " + name);
			};
		}

		@SuppressWarnings("MethodWithTooManyParameters")
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			var mapConstructor = (Supplier<Map<Object, Object>>) HashMap::new;
			List<Accessor> accessors = new ArrayList<>();
			Accessor mapper = null;
			if ("(".equals(delimiter))
			{
				var start = position.getIndex() - name.length() - 2;
				if (startsWith(expression, position,">"))
				{
					mapConstructor = TreeMap::new;
					position.setIndex(position.getIndex() + 1);
				}
				delimiter = CompositeAccessor.parseAll(expression, position, "#", ",|\\)", false, macroLibrary, accessors);
				if (!"count".equals(name) && ",".equals(delimiter))
				{
					List<Accessor> mappers = new ArrayList<>();
					delimiter = CompositeAccessor.parse(expression, position, "\\)", macroLibrary, mappers);
					mapper = mappers.get(0);
				}
				checkClosingParenthesis(name, delimiter);
				delimiter = close(expression, start, position, endMarkers);
			}
			else
			{
				accessors.add(new AccessorChain(Collections.emptyList()));
			}
			result.add(new IndexingInstruction(name, accessors, mapConstructor, mapper));
			return delimiter;
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			var result = mapConstructor.get();
			indexer.set(result);
			for (var element: asIterable(source))
			{
				worker.accept(context.derive(element));
			}
			return result;
		}
	}

	/**
	 * This processing instruction can be invoked on a Map and performs
	 * a lookup using the provided access path.
	 */
	private static class IndexAccessInstruction extends ProcessingInstruction
	{
		private final IndexedData<Object, Object> indexer;

		private IndexAccessInstruction(List<Accessor> accessors)
		{
			indexer = new IndexedData<>(accessors, null);
		}

		@SuppressWarnings("MethodWithTooManyParameters")
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			checkOpeningParenthesis(name, delimiter);
			var start = position.getIndex() - name.length() - 2;
			List<Accessor> accessors = new ArrayList<>();
			delimiter = CompositeAccessor.parseAll(expression, position, "#", "\\)", false, macroLibrary, accessors);
			checkClosingParenthesis(name, delimiter);
			result.add(new IndexAccessInstruction(accessors));
			return close(expression, start, position, endMarkers);
		}

		@SuppressWarnings("unchecked")
		@Override
		Object access(Object source, Proxy<?> context)
		{
			Map<Object, Object> index = unwrap(source);
			indexer.set(index);
			return indexer.get((Proxy<Object>) context);
		}
	}

	/**
	 * This processing instruction concatenates several collections or values into
	 * one result collection.
	 */
	private static class UnionInstruction extends ProcessingInstruction
	{
		private final List<Accessor> accessors;

		private UnionInstruction(List<Accessor> accessors)
		{
			this.accessors = accessors;
		}

		@SuppressWarnings("MethodWithTooManyParameters")
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			checkOpeningParenthesis(name, delimiter);
			var start = position.getIndex() - name.length() - 2;
			List<Accessor> accessors = new ArrayList<>();
			delimiter = CompositeAccessor.parseAll(expression, position, ",", "\\)", false, macroLibrary, accessors);
			checkClosingParenthesis(name, delimiter);
			result.add(new UnionInstruction(accessors));
			return close(expression, start, position, endMarkers);
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			var result = new ArrayList<>();
			for (var accessor: accessors)
			{
				for (var value: asIterable(accessor.access(source, context)))
				{
					result.add(value);
				}
			}
			return result;
		}
	}

	/**
	 * This processing instruction constructs a Map that contains
	 * the values computed according to the specification
	 */
	private static class ConstructInstruction extends ProcessingInstruction
	{
		private final List<Accessor> accessors;

		private ConstructInstruction(List<Accessor> accessors)
		{
			this.accessors = accessors;
		}

		@SuppressWarnings("MethodWithTooManyParameters")
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			checkOpeningParenthesis(name, delimiter);
			var start = position.getIndex() - name.length() - 2;
			List<Accessor> accessors = new ArrayList<>();
			delimiter = CompositeAccessor.parseAll(expression, position, ",", "\\)", null, macroLibrary, accessors);
			checkClosingParenthesis(name, delimiter);
			result.add(new ConstructInstruction(accessors));
			return close(expression, start, position, endMarkers);
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			var result = new HashMap<String, Object>();
			for (var accessor: accessors)
			{
				Object key = accessor.getKey();
				Object value = unwrap(accessor.access(source, context));
				if (key instanceof List)
				{
					var input = OrionQL.Accessor.asIterable(value).iterator();
					for (var name: (List<?>) key)
					{
						result.put(name.toString(), input.hasNext() ? input.next() : null);
					}
				}
				else if ("*".equals(key))
				{
					if (value != null)
					{
						if (!(value instanceof Map))
						{
							throw new RuntimeException("Map decomposition not supported for " + value.getClass());
						}
						for (var entry: ((Map<?, ?>) value).entrySet())
						{
							result.put(entry.getKey().toString(), entry.getValue());
						}
					}
				}
				else
				{
					result.put(accessor.getName(), value);
				}
			}
			return result;
		}
	}

	/**
	 * This processing instruction turns an Iterable into an HTML table
	 */
	private static class TableInstruction extends ProcessingInstruction
	{
		private final TabularData writer;

		private TableInstruction(TabularData writer)
		{
			this.writer = writer;
		}

		@SuppressWarnings("MethodWithTooManyParameters")
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			checkOpeningParenthesis(name, delimiter);
			var start = position.getIndex() - name.length() - 2;
			List<Accessor> accessors = new ArrayList<>();
			delimiter = CompositeAccessor.parseAll(expression, position, ",|\n", "\\)", null, macroLibrary, accessors);
			checkClosingParenthesis(name, delimiter);
			TabularData writer = new TabularData(accessors);
			if ("htable".equals(name))
			{
				writer.setHorizontalLayout();
			}
			result.add(new TableInstruction(writer));
			return close(expression, start, position, endMarkers);
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			var result = new ByteArrayOutputStream();
			writer.setOutputStream(result, "UTF-8");
			try
			{
				writer.writeHtmlHeader();
				for (var element: asIterable(source))
				{
					writer.writeHtmlRecord(context.derive(element));
				}
				writer.writeHtmlFooter();
			}
			catch (IOException e)
			{
				throw new RuntimeException(e);
			}
			return new HtmlContainer(result.toString(), null);
		}
	}

	/**
	 * This processing instruction turns a nested Map into an HTML table
	 */
	private static class GridInstruction extends ProcessingInstruction
	{
		private final Integer numCaptionLevels;
		private final boolean doTotals;
		private final boolean rotateHeaders;
		private final IndexedData.CellMapper cellMapper;

		private GridInstruction(Integer numCaptionLevels, boolean doTotals, boolean rotateHeaders, IndexedData.CellMapper cellMapper)
		{
			this.numCaptionLevels = numCaptionLevels;
			this.doTotals = doTotals;
			this.rotateHeaders = rotateHeaders;
			this.cellMapper = cellMapper;
		}

		@SuppressWarnings("MethodWithTooManyParameters")
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			var numCaptionLevels = 0;
			var doTotals = false;
			var rotateHeaders = false;
			IndexedData.CellMapper cellMapper = null;
			if ("(".equals(delimiter))
			{
				var start = position.getIndex() - name.length() - 2;
				var accessors = new ArrayList<Accessor>();
				delimiter = CompositeAccessor.parse(expression, position, ",|\\)", macroLibrary, accessors);
				cellMapper = new IndexedData.CellMapper(accessors.get(0));
				while (",".equals(delimiter))
				{
					var buffer = new StringBuilder();
					delimiter = readInput(expression, position, ",|\\)", true, buffer);
					switch (buffer.toString())
					{
						case "totals" -> doTotals = true;
						case "rotate" -> rotateHeaders = true;
						default -> numCaptionLevels = Integer.parseInt(buffer.toString());
					}
				}
				checkClosingParenthesis(name, delimiter);
				delimiter = close(expression, start, position, endMarkers);
			}
			result.add(new GridInstruction(numCaptionLevels, doTotals, rotateHeaders, cellMapper));
			return delimiter;
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			var cellMapper = this.cellMapper;
			var doTotals = this.doTotals;
			var rotateHeaders = this.rotateHeaders;
			Map<Object, Object> input = unwrap(source);
			if (cellMapper == null)
			{
				var leaf = IndexedData.peek(input);
				if (leaf != null)
				{
					var value = leaf.getValue();
					if (leaf.getKey() > 2 + numCaptionLevels)
					{
						cellMapper = IndexedData.CellMapper.PPRINT;
					}
					else if (value instanceof Iterable)
					{
						cellMapper = IndexedData.CellMapper.JOIN;
					}
					else if ((value instanceof Number) || ((value instanceof HtmlContainer) && ((HtmlContainer) value).isNumeric()))
					{
						doTotals = true;
					}
				}
			}
			var result = new ByteArrayOutputStream();
			try
			{
				IndexedData.writeHtmlMatrices(input, numCaptionLevels, result, "UTF-8", cellMapper, doTotals, rotateHeaders, false);
			}
			catch (IOException e)
			{
				throw new RuntimeException(e);
			}
			return new HtmlContainer(result.toString(), null);
		}
	}

	/**
	 * This processing instruction renders its input as a bar chart or line chart
	 */
	private static class ChartInstruction extends ProcessingInstruction
	{
		private final boolean isLinechart;
		private final List<Accessor> accessors;
		private final double scale;
		private final boolean isMultiSeries;

		private ChartInstruction(boolean isLinechart, List<Accessor> accessors, double scale, boolean hasDynamicSeries)
		{
			this.isLinechart = isLinechart;
			this.accessors = accessors;
			this.scale = scale;
			this.isMultiSeries = hasDynamicSeries || (accessors.size() > 1);
		}

		@SuppressWarnings("MethodWithTooManyParameters")
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			List<Accessor> accessors = new ArrayList<>();
			if ("(".equals(delimiter))
			{
				var start = position.getIndex() - name.length() - 2;
				delimiter = CompositeAccessor.parseAll(expression, position, ",", "\\)", null, macroLibrary, accessors);
				checkClosingParenthesis(name, delimiter);
				delimiter = close(expression, start, position, endMarkers);
			}
			var scale = 1.0;
			if ((!accessors.isEmpty()) && (accessors.get(0) instanceof LiteralAccessor))
			{
				var scaleAccessor = (LiteralAccessor) accessors.remove(0);
				var scaleValue = scaleAccessor.value;
				if (!(scaleValue instanceof Number))
				{
					throw new RuntimeException("Scale must be a number");
				}
				scale = ((Number) scaleValue).doubleValue();
			}
			if (accessors.isEmpty())
			{
				accessors.add(new AccessorChain(Collections.emptyList()));
			}
			if ((accessors.size() == 1) && !accessors.get(0).hasKey())
			{
				accessors.get(0).setKey("#black");
			}
			boolean hasDynamicSeries = false;
			var parser = Pattern.compile("([^#]+)?(?:(#[0-9a-f]{6})|#([a-z]+))");
			for (var accessor: accessors)
			{
				String label = null;
				String color;
				if (accessor.hasKey())
				{
					String spec = accessor.getKey().toString();
					if ("*".equals(spec))
					{
						hasDynamicSeries = true;
						continue;
					}
					var matcher = parser.matcher(spec);
					if (matcher.matches())
					{
						label = matcher.group(1);
						color = (matcher.group(2) == null) ? matcher.group(3) : matcher.group(2);
					}
					else
					{
						label = spec;
						color = makeColor(spec);
					}
				}
				else
				{
					color = makeColor(accessor.getExpression());
				}
				label = (label == null) ? "" : label + ": ";
				accessor.setKey(String.format("title=\"%s%%s\" style=\"background-color: %s; cursor: pointer;\"", label, color));
			}
			result.add(new ChartInstruction("linechart".equals(name), accessors, scale, hasDynamicSeries));
			return delimiter;
		}

		@SuppressWarnings("unchecked")
		@Override
		Object access(Object source, Proxy<?> context)
		{
			Number numericValue = null;
			var chart = new StringBuilder();
			chart.append("<span class=\"chart\">");
			if (isLinechart)
			{
				var points = new TreeMap<Double, List<String>>();
				for (var accessor : accessors)
				{
					var value = unwrap(accessor.access(source, context));
					if (value != null)
					{
						String spec = accessor.getKey().toString();
						if ("*".equals(spec))
						{
							if (!(value instanceof Map))
							{
								throw new RuntimeException("Dynamic series require a Map");
							}
							for (var point: ((Map<String, Object>) value).entrySet())
							{
								if (!(point.getValue() instanceof Number))
								{
									throw new RuntimeException("Not a number: " + point.getValue());
								}
								double doubleValue = ((Number) point.getValue()).doubleValue();
								points.computeIfAbsent(doubleValue, aDouble -> new ArrayList<>()).add(String.format("title=\"%s: %s\" style=\"background-color: %s; cursor: pointer;\"", point.getKey(), point.getValue(), makeColor(point.getKey())));
							}
						}
						else
						{
							if (!(value instanceof Number))
							{
								throw new RuntimeException("Not a number: " + value);
							}
							numericValue = (Number) value;
							double doubleValue = ((Number) value).doubleValue();
							points.computeIfAbsent(doubleValue, aDouble -> new ArrayList<>()).add(String.format(spec, value));
						}
					}
				}
				var current = 0;
				// we cannot use the Unicode characters below directly since when writing to the
				// output stream getBytes() will mess up the encoding (the full circle in :link()
				// is not affected for some reason)
				chart.append("<span style=\"background-color: black;\">&thinsp;</span>");
				for (var entry: points.entrySet())
				{
					int position = (int) (entry.getKey() * scale + 0.5);
					if (position > 1000)
					{
						throw new RuntimeException("Value too large: " + entry.getKey());
					}
					// assuming that a thinspace is twice as wide as a hairspace, we place
					// its center at the computed position
					int span = position - current - 1;
					if (span > 0)
					{
						chart.append(String.format("<span>%s</span>", "&hairsp;".repeat(span)));
						current = current + span;
					}
					for (var point: entry.getValue())
					{
						chart.append(String.format("<span %s>&thinsp;</span>", point));
						current = current + 2;
					}
				}
				if (isMultiSeries)
				{
					numericValue = null;
				}
			}
			else
			{
				var total = 0.0;
				Boolean isIntegerChart = null;
				for (var accessor : accessors)
				{
					var value = unwrap(accessor.access(source, context));
					if (value != null)
					{
						String spec = accessor.getKey().toString();
						if ("*".equals(spec))
						{
							if (!(value instanceof Map))
							{
								throw new RuntimeException("Dynamic series require a Map");
							}
							for (var point: ((Map<String, Object>) value).entrySet())
							{
								if (!(point.getValue() instanceof Number))
								{
									throw new RuntimeException("Not a number: " + point.getValue());
								}
								if (isIntegerChart == null)
								{
									isIntegerChart = (point.getValue() instanceof Integer);
								}
								double doubleValue = ((Number) point.getValue()).doubleValue();
								total += doubleValue;
								int span = (int) (doubleValue * scale + 0.5);
								if ((doubleValue > 0) && (span == 0))
								{
									span = 1;
								}
								if (span > 1000)
								{
									throw new RuntimeException("Value too large: " + value);
								}
								chart.append(String.format("<span title=\"%s: %s\" style=\"background-color: %s; cursor: pointer;\">%s</span>", point.getKey(), point.getValue(), makeColor(point.getKey()), "&hairsp;".repeat(span)));
							}
						}
						else
						{
							if (!(value instanceof Number))
							{
								throw new RuntimeException("Not a number: " + value);
							}
							if (isIntegerChart == null)
							{
								isIntegerChart = (value instanceof Integer);
							}
							double doubleValue = ((Number) value).doubleValue();
							total += doubleValue;
							int span = (int) (doubleValue * scale + 0.5);
							if ((doubleValue > 0) && (span == 0))
							{
								span = 1;
							}
							if (span > 1000)
							{
								throw new RuntimeException("Value too large: " + value);
							}
							chart.append(String.format("<span %s>%s</span>", String.format(accessor.getKey().toString(), value), "&hairsp;".repeat(span)));
						}
					}
				}
				numericValue = ((isIntegerChart != null) && isIntegerChart) ? (Number) Double.valueOf(total).intValue() : total;
			}
			chart.append("</span>");
			return new HtmlContainer(chart.toString(), numericValue);
		}

		private static String makeColor(Object label)
		{
			try
			{
				MessageDigest md5 = MessageDigest.getInstance("MD5");
				md5.update(StandardCharsets.UTF_8.encode(label.toString()));
				return String.format("#%032x", new BigInteger(1, md5.digest())).substring(0, 7);
			}
			catch (NoSuchAlgorithmException e)
			{
				throw new RuntimeException("Unable to compute auto color (MD5 algorithm not available)");
			}
		}
	}

	/**
	 * This processing instruction will return a lazy collection (an Iterable)
	 * containing the leaf objects of a collection hierarchy where on each level
	 * the next level collection is retrieved from every collection element by an
	 * OrionQL expression.
	 */
	private static class FlatteningInstruction extends ProcessingInstruction
	{
		private static class FlatteningCollection implements Iterable<Object>
		{
			private static class FlatteningIterator implements Iterator<Object>
			{
				private static class RecursionControl
				{
					private boolean includeRootObject = false;
					private Integer maxDepth = null;
					private CerberusLogic.Selector breakSelector = null;
				}

				private final List<Accessor> path;
				private final boolean isRecursive;
				private final LinkedList<Iterator<Object>> iterationState = new LinkedList<>();
				private final LinkedList<Proxy<?>> parentHierarchy = new LinkedList<>();
				private final LinkedList<Object> loopTracker;

				/**
				 * Iterate over an object tree hierarchically by applying on each
				 * iteration level the corresponding expression from the list and
				 * iterating over the result.
				 *
				 * @param accessors list of accessors representing the expressions for each iteration level
				 * @param source    root of object hierarchy to iterate over
				 * @param context   the expression evaluation context, which is the proxy from which
				 *                  the expression evaluation started
				 */
				private FlatteningIterator(List<Accessor> accessors, Object source, Proxy<?> context)
				{
					path = accessors;
					isRecursive = false;
					loopTracker = null;
					// if source != context, source will be established as an intermediate parent
					setupIteration(context.derive(source), accessors.get(0));
				}

				/**
				 * This constructor is only used privately by the FlatteningIterator itself
				 * to set up, for the respective level, a recursive iteration that applies
				 * the given expression repeatedly.
				 *
				 * @param accessor accessor representing the expression to iterate over recursively
				 *                 (if key == "*", the root object is included in the output)
				 * @param source   root of object hierarchy to iterate over
				 */
				private FlatteningIterator(Accessor accessor, Proxy<?> source)
				{
					path = List.of(accessor);
					isRecursive = true;
					loopTracker = new LinkedList<>();
					if (((RecursionControl) accessor.getKey()).includeRootObject)
					{
						// make the top level iteration yield the source object itself
						iterationState.push(Collections.singletonList((Object) source).iterator());
						parentHierarchy.push(source);
					}
					else
					{
						setupIteration(source, accessor);
					}
				}

				/**
				 * Set up the iteration on the collection retrieved from the <tt>input</tt> object
				 * by the Accessor given in <tt>collectionAccessor</tt>.
				 *
				 * @param input              object to derive the next iteration from
				 * @param collectionAccessor accessor retrieving from the input object the collection to iterate over
				 */
				private void setupIteration(Proxy<?> input, Accessor collectionAccessor)
				{
					if (!isRecursive && collectionAccessor.hasKey())
					{
						// we are in the non-recursive iterator and need to set up a recursive iteration
						iterationState.push(new FlatteningIterator(collectionAccessor, input));
						parentHierarchy.push(input);
					}
					else
					{
						// use the internal access method to keep wrapped scalars wrapped
						var collection = collectionAccessor.access(input, input);
						if (collection != null)
						{
							iterationState.push(asIterable(collection).iterator());
							parentHierarchy.push(input);
							if (loopTracker != null)
							{
								loopTracker.push(unwrap(input));
							}
						}
					}
				}

				private void discardIteration()
				{
					iterationState.pop();
					parentHierarchy.pop();
					if ((loopTracker != null) && !loopTracker.isEmpty())
					{
						loopTracker.pop();
					}
				}

				@Override
				public boolean hasNext()
				{
					while (true)
					{
						// if the root iterator is exhausted (or there wasn't one at all), we are done
						if (iterationState.isEmpty())
						{
							return false;
						}
						// if an iterator is exhausted, discard it and return to the parent one
						if (!iterationState.peek().hasNext())
						{
							discardIteration();
						}
						// recursive iterators yield all objects along the path
						else if (isRecursive)
						{
							return true;
						}
						// fill the iterator stack up to the leaf iteration level
						else if (iterationState.size() < path.size())
						{
							// noinspection ConstantConditions
							var element = parentHierarchy.peek().derive(iterationState.peek().next());
							setupIteration(element, path.get(iterationState.size()));
						}
						else
						{
							// if we got here, the stack is filled and the leaf iterator has a next element
							return true;
						}
					}
				}

				@Override
				public Object next()
				{
					// noinspection ConstantConditions
					var element = iterationState.peek().next();
					// noinspection ConstantConditions
					var result = parentHierarchy.peek().derive(element);
					if (isRecursive)
					{
						var control = ((RecursionControl) path.get(0).getKey());
						if (((control.maxDepth == null) || iterationState.size() < control.maxDepth) &&
								((control.breakSelector == null) || !control.breakSelector.matches(result)) &&
								(!loopTracker.contains(element)))
						{
							setupIteration(result, path.get(0));
						}
					}
					return result;
				}
			}

			private final List<Accessor> accessors;
			private final Object source;
			private final Proxy<?> context;

			private FlatteningCollection(List<Accessor> accessors, Object source, Proxy<?> context)
			{
				this.accessors = accessors;
				this.source = source;
				this.context = context;
			}

			@Override
			@NotNull
			public Iterator<Object> iterator()
			{
				return new FlatteningIterator(accessors, source, context);
			}
		}

		private final List<Accessor> accessors;

		private FlatteningInstruction(List<Accessor> accessors)
		{
			this.accessors = accessors;
		}

		@SuppressWarnings("MethodWithTooManyParameters")
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			checkOpeningParenthesis(name, delimiter);
			var start = position.getIndex() - name.length() - 2;
			List<Accessor> accessors = new ArrayList<>();
			delimiter = "#";
			while ("#".equals(delimiter))
			{
				delimiter = CompositeAccessor.parse(expression, position, "\\*|\\+|#|\\)", macroLibrary, accessors);
				if ("*".equals(delimiter) || "+".equals(delimiter))
				{
					var key = new FlatteningCollection.FlatteningIterator.RecursionControl();
					key.includeRootObject = "*".equals(delimiter);
					delimiter = close(expression, start, position, ",|#|\\)");
					if (",".equals(delimiter))
					{
						var optionStart = position.getIndex();
						var buffer = new StringBuilder();
						delimiter = readInput(expression, position, ",|#|\\)", true, buffer);
						if (buffer.toString().matches("\\d+"))
						{
							var maxDepth = Integer.decode(buffer.toString());
							key.maxDepth = key.includeRootObject ? maxDepth + 1 : maxDepth;
						}
						else
						{
							position.setIndex(optionStart);
							delimiter = ",";
						}
					}
					if (",".equals(delimiter))
					{
						var buffer = new StringBuilder();
						delimiter = readInputSyntaxAware(expression, position, "#|\\)", buffer);
						key.breakSelector = CerberusLogic.compileSelector(buffer.toString());
					}
					accessors.get(accessors.size() - 1).setKey(key);
				}
			}
			checkClosingParenthesis(name, delimiter);
			result.add(new FlatteningInstruction(accessors));
			return close(expression, start, position, endMarkers);
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			return new FlatteningCollection(accessors, source, context);
		}
	}

	/**
	 * This processing instruction performs lookups in the database or a provided
	 * lookup table.
	 */
	private static class LookupInstruction extends ProcessingInstruction
	{
		private enum QueryTypeEnum
		{
			SINGLE,
			LIST,
			COUNT,
			STREAM
		}


		private final String name;
		private final Class<? extends SailPointObject> objectClass;
		private final Accessor keyExpression;
		private final QueryTypeEnum queryType;
		private final List<String> attributes;
		private final QueryOptions baseQueryOptions;

		@SuppressWarnings("unchecked")
		private LookupInstruction(String name, Accessor keyExpression, QueryTypeEnum queryType, List<String> attributes, QueryOptions baseQueryOptions, String optionString)
		{
			if (Character.isLowerCase(name.charAt(0)))
			{
				this.name = name;
				objectClass = null;
				if ((queryType != QueryTypeEnum.SINGLE) || (attributes != null) || !optionString.isEmpty())
				{
					throw new RuntimeException("No query modifiers allowed for application defined lookups");
				}
			}
			else
			{
				try
				{
					objectClass = (Class<? extends SailPointObject>) Class.forName("sailpoint.object." + name);
					//@formatter:off
					// this will establish separate caches for the different types of queries
					this.name = String.format("%s%s%s%s%s%s",
						name,
						(queryType == QueryTypeEnum.COUNT) ? "?" : "",
						(queryType == QueryTypeEnum.LIST) ? "[]" : "",
						(queryType == QueryTypeEnum.STREAM) ? ">" : "",
						(attributes != null) ? String.format("{%s}", String.join(",", attributes)) : "",
						optionString.isEmpty() ? "" : String.format(",%s", optionString)
					);
					//@formatter:on
				}
				catch (ClassNotFoundException e)
				{
					//noinspection ThrowInsideCatchBlockWhichIgnoresCaughtException
					throw new RuntimeException("Unknown object class: " + name);
				}
			}
			if ((keyExpression != null) && ((TemplateSubstitution) keyExpression).isEmpty())
			{
				keyExpression = null;
			}
			this.keyExpression = keyExpression;
			this.queryType = queryType;
			this.attributes = attributes;
			this.baseQueryOptions = baseQueryOptions;
		}

		@SuppressWarnings("MethodWithTooManyParameters")
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			if ("(".equals(delimiter))
			{
				var start = position.getIndex() - name.length() - 2;
				if (startsWith(expression, position,"?"))
				{
					position.setIndex(position.getIndex() + 1);
					var accessors = new ArrayList<Accessor>();
					delimiter = TemplateSubstitution.parse(expression, position, "\\)", macroLibrary, accessors);
					result.add(new LookupInstruction(name, accessors.get(0), QueryTypeEnum.COUNT, null, null, ""));
				}
				else
				{
					var queryType = QueryTypeEnum.SINGLE;
					var argumentsEndMarker = "\\)";
					if (startsWith(expression, position,"["))
					{
						queryType = QueryTypeEnum.LIST;
						position.setIndex(position.getIndex() + 1);
						argumentsEndMarker = "\\]";
					}
					else if (startsWith(expression, position,">"))
					{
						queryType = QueryTypeEnum.STREAM;
						position.setIndex(position.getIndex() + 1);
					}
					var delimiterPatterns = combineRE(argumentsEndMarker, ",");
					List<String> attributes = null;
					if (startsWith(expression, position,"{"))
					{
						position.setIndex(position.getIndex() + 1);
						attributes = new ArrayList<>();
						delimiter = ",";
						while (",".equals(delimiter))
						{
							var buffer = new StringBuilder();
							delimiter = readInput(expression, position, ", *|\\}", true, buffer);
							attributes.add(buffer.toString());
						}
						if (!"}".equals(delimiter))
						{
							throw new RuntimeException(String.format("Malformed %s load instruction", name));
						}
					}
					var accessors = new ArrayList<Accessor>();
					delimiter = TemplateSubstitution.parse(expression, position, delimiterPatterns, macroLibrary, accessors);
					var keyExpression = accessors.get(0);
					var queryOptions = new QueryOptions();
					var optionsStart = position.getIndex();
					while (",".equals(delimiter))
					{
						var buffer = new StringBuilder();
						delimiter = readInput(expression, position, delimiterPatterns, true, buffer);
						var option = buffer.toString();
						if (option.matches("\\d+"))
						{
							queryOptions.setResultLimit(Integer.parseInt(option));
							break;
						}
						else
						{
							queryOptions.addOrdering(option.replaceAll(" .*", ""), !option.endsWith(" desc"));
						}
					}
					var optionString = expression.substring(optionsStart, position.getIndex());
					if (queryType == QueryTypeEnum.LIST)
					{
						if (!"]".equals(delimiter))
						{
							throw new RuntimeException(String.format("%s load instruction not properly terminated", name));
						}
						delimiter = close(expression, start, position, "\\)");
					}
					if ((queryType == QueryTypeEnum.STREAM) && (queryOptions.getResultLimit() == 1))
					{
						// A stream of length 1 behaves exactly as a list of length 1, returning
						// the one object or null - but the expression is easier to write.
						queryType = QueryTypeEnum.LIST;
					}
					result.add(new LookupInstruction(name, keyExpression, queryType, attributes, queryOptions, optionString));
				}
				checkClosingParenthesis(name, delimiter);
				return close(expression, start, position, endMarkers);
			}
			else
			{
				result.add(new LookupInstruction(name, null, QueryTypeEnum.SINGLE, null, new QueryOptions(), ""));
			}
			return delimiter;
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			var lookup = context.lookupTables.get(name);
			if (lookup == null)
			{
				if ((objectClass == null) || (context.resolver == null))
				{
					throw new RuntimeException("Undefined lookup: " + name);
				}
				lookup = new HashMap<>();
				context.lookupTables.put(name, lookup);
			}
			var key = unwrap((keyExpression == null) ? source : keyExpression.access(source, context));
			if (lookup.containsKey(key))
			{
				return lookup.get(key);
			}
			if ((objectClass == null) || (context.resolver == null))
			{
				return null;
			}
			if (!(key instanceof String))
			{
				throw new RuntimeException(String.format("Cannot use %s for database lookup", key.getClass().getSimpleName()));
			}
			try
			{
				Filter filter = null;
				if (keyExpression == null)
				{
					try
					{
						// this is a simplifying shortcut, but we normally shouldn't get more than one hit
						filter = Filter.or(Filter.eq("id", key), Filter.eq("name", key));
						// sadly, there is no other way to determine if 'name' is searchable than to use the filter
						context.resolver.countObjects(objectClass, new QueryOptions(filter));
					}
					catch (GeneralException e)
					{
						if (!String.format("could not resolve property: name of: %s", objectClass.getName()).equals(e.getMessage()))
						{
							throw e;
						}
						filter = Filter.eq("id", key);
					}
				}
				else if (!"-".equals(key))
				{
					filter = Filter.compile(key.toString());
				}
				Object result = null;
				if (queryType == QueryTypeEnum.COUNT)
				{
					result = context.resolver.countObjects(objectClass, (filter == null) ? new QueryOptions() : new QueryOptions(filter));
				}
				else
				{
					var queryOptions = new QueryOptions(baseQueryOptions);
					if (filter != null)
					{
						queryOptions.addFilter(filter);
					}
					if (attributes == null)
					{
						switch (queryType)
						{
							case SINGLE ->
								//noinspection VariableNotUsedInsideIf
								result = (keyExpression == null) ?
										context.resolver.getObject(objectClass, key.toString()) :
										context.resolver.getUniqueObject(objectClass, filter);
							case LIST -> {
								var objects = context.resolver.getObjects(objectClass, queryOptions);
								if (baseQueryOptions.getResultLimit() == 1)
								{
									result = objects.isEmpty() ? null : objects.get(0);
								}
								else
								{
									result = objects;
								}
							}
							case STREAM -> {
								var resultSet = QueryHelper.search(context.resolver, objectClass, queryOptions);
								// ensure the result will not be cached below by returning it immediately
								return (Iterable<Object>) () -> new QueryHelper.TransformingIterator<>(resultSet, o -> o);
							}
						}
					}
					else
					{
						// (Note that also for single-attribute queries we use QueryHelper and fetch
						// the data as maps extracting the attribute afterward. The optimization to
						// use IdIterator instead would not allow us to fetch extended attributes.)
						var attribute = (attributes.size() == 1) ? attributes.get(0) : null;
						Function<Map<String, Object>, Object> mapper = (attribute != null) ? o -> o.get(attribute) : o -> o;
						var resultSet = QueryHelper.search(context.resolver, objectClass, queryOptions, attributes);
						if (resultSet.hasNext())
						{
							switch (queryType)
							{
								case SINGLE:
									var row = resultSet.next();
									if (!resultSet.hasNext())
									{
										result = mapper.apply(row);
									}
									break;
								case LIST:
									if (baseQueryOptions.getResultLimit() == 1)
									{
										result = mapper.apply(resultSet.next());
									}
									else
									{
										var objects = new ArrayList<>();
										resultSet.forEachRemaining(o -> objects.add(mapper.apply(o)));
										result = objects;
									}
									break;
								case STREAM:
									// ensure the result will not be cached below by returning it immediately
									return (Iterable<Object>) () -> new QueryHelper.TransformingIterator<>(resultSet, mapper);
							}
						}
						else if ((queryType == QueryTypeEnum.LIST) && (baseQueryOptions.getResultLimit() != 1))
						{
							result = Collections.emptyList();
						}
					}
				}
				lookup.put(key, result);
				return result;
			}
			catch (GeneralException e)
			{
				throw new RuntimeException(e);
			}
		}
	}

	/**
	 * This processing instruction extracts matches of the provided regular expression
	 * from a String.
	 */
	private static class ExtractInstruction extends ProcessingInstruction
	{
		private static final Pattern groupExtractor = Pattern.compile("\\(\\?<([A-Za-z][A-Za-z0-9]*)>");

		private final Pattern pattern;
		private final HashSet<String> groupNames;
		private final boolean isSingleExtract;

		private ExtractInstruction(String pattern, boolean isSingleExtract)
		{
			this.pattern = Pattern.compile(pattern);
			this.isSingleExtract = isSingleExtract;
			var groupNames = new HashSet<String>();
			var groupMatcher = groupExtractor.matcher(pattern);
			while (groupMatcher.find())
			{
				groupNames.add(groupMatcher.group(1));
			}
			this.groupNames = groupNames.isEmpty() ? null : groupNames;
		}

		@SuppressWarnings({"MethodWithTooManyParameters", "unused"})
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			checkOpeningParenthesis(name, delimiter);
			var start = position.getIndex() - name.length() - 2;
			var buffer = new StringBuilder();
			checkClosingParenthesis(name, readInput(expression, position, "\\)", false, buffer));
			result.add(new ExtractInstruction(buffer.toString(), "match".equals(name)));
			return close(expression, start, position, endMarkers);
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			source = unwrap(source);
			if (source == null)
			{
				return null;
			}
			var matches = new ArrayList<>();
			var matcher = pattern.matcher(source.toString());
			while (matcher.find())
			{
				Object match;
				if (groupNames != null)
				{
					var row = new HashMap<String, String>();
					for (var groupName: groupNames)
					{
						row.put(groupName, matcher.group(groupName));
					}
					match = row;
				}
				else if (matcher.groupCount() > 1)
				{
					var row = new ArrayList<String>(matcher.groupCount());
					for (var groupIndex = 1; groupIndex <= matcher.groupCount(); ++groupIndex)
					{
						row.add(matcher.group(groupIndex));
					}
					match = row;
				}
				else
				{
					match = matcher.group(matcher.groupCount());
				}
				if (isSingleExtract)
				{
					return match;
				}
				matches.add(match);
			}
			return isSingleExtract ? null : matches;
		}
	}

	/**
	 * This processing instruction replaces matches of the provided regular expression
	 * with a provided replacement expression.
	 */
	private static class ReplaceInstruction extends ProcessingInstruction
	{
		private final Pattern pattern;
		private final String replacement;

		private ReplaceInstruction(String pattern, String replacement)
		{
			this.pattern = Pattern.compile(pattern);
			this.replacement = replacement;
		}

		@SuppressWarnings({"MethodWithTooManyParameters", "unused"})
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			checkOpeningParenthesis(name, delimiter);
			var start = position.getIndex() - name.length() - 2;
			var regexp = new StringBuilder();
			var replacement = new StringBuilder();
			delimiter = readInput(expression, position, ",", false, regexp);
			if (!",".equals(delimiter))
			{
				throw new RuntimeException(String.format("Not enough parameters for %s instruction", name));
			}
			delimiter = readInput(expression, position, "\\)", false, replacement);
			checkClosingParenthesis(name, delimiter);
			result.add(new ReplaceInstruction(regexp.toString(), replacement.toString()));
			return close(expression, start, position, endMarkers);
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			source = unwrap(source);
			if (source == null)
			{
				return null;
			}
			var matcher = pattern.matcher(source.toString());
			return matcher.replaceAll(replacement);
		}
	}

	/**
	 * This processing instruction allows to query the environment the expression is executed in.
	 */
	private static class EnvironmentInstruction extends ProcessingInstruction
	{
		private final String name;

		private EnvironmentInstruction(String name)
		{
			this.name = name;
		}

		@SuppressWarnings({"MethodWithTooManyParameters", "unused"})
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			if ("(".equals(delimiter))
			{
				var buffer = new StringBuilder();
				var start = position.getIndex() - name.length() - 2;
				checkClosingParenthesis(name, readInput(expression, position, "\\)", false, buffer));
				delimiter = close(expression, start, position, endMarkers);
				result.add(new EnvironmentInstruction(buffer.toString()));
			}
			else
			{
				result.add(new EnvironmentInstruction(null));
			}
			return delimiter;
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			if (context.environment == null)
			{
				return null;
			}
			else
			{
				return (name == null) ? context.environment : context.environment.get(name);
			}
		}
	}

	/**
	 * This processing instruction performs user defined caching of expression
	 * evaluation results.
	 */
	private static class CachingInstruction extends ProcessingInstruction
	{
		private final Accessor cachingKeyExpression;
		private final Accessor payloadExpression;
		private final boolean isTolerant;
		private final boolean isRecache;
		private final boolean isSwap;

		private CachingInstruction(Accessor cachingKeyExpression, Accessor payloadExpression, boolean isTolerant, boolean isRecache, boolean isSwap)
		{
			this.cachingKeyExpression = cachingKeyExpression;
			this.payloadExpression = payloadExpression;
			this.isTolerant = isTolerant;
			this.isRecache = isRecache;
			this.isSwap = isSwap;
		}

		@SuppressWarnings("MethodWithTooManyParameters")
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			checkOpeningParenthesis(name, delimiter);
			var start = position.getIndex() - name.length() - 2;
			var isSwap = "swapcache".equals(name);
			var isRecache = isSwap || "recache".equals(name);
			var isTolerant = false;
			var accessors = new ArrayList<Accessor>();
			delimiter = TemplateSubstitution.parse(expression, position, "\\?|,|\\)", macroLibrary, accessors);
			if ("?".equals(delimiter))
			{
				isTolerant = true;
				delimiter = close(expression, start, position, ",|\\)");
			}
			if (",".equals(delimiter))
			{
				if (isTolerant)
				{
					throw new RuntimeException(String.format("Conflicting %s parameter combination", name));
				}
				delimiter = CompositeAccessor.parse(expression, position, "\\)", macroLibrary, accessors);
			}
			else if (isRecache)
			{
				throw new RuntimeException(String.format("Not enough parameters for %s instruction", name));
			}
			checkClosingParenthesis(name, delimiter);
			result.add(new CachingInstruction(accessors.get(0), (accessors.size() > 1) ? accessors.get(1) : null, isTolerant, isRecache, isSwap));
			return close(expression, start, position, endMarkers);
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			var key = cachingKeyExpression.access(source, context);
			if (!isRecache && context.userCache.containsKey(key))
			{
				return context.userCache.get(key);
			}
			if (payloadExpression == null)
			{
				if (isTolerant)
				{
					return null;
				}
				throw new RuntimeException("No value cached for " + key);
			}
			var oldValue = context.userCache.get(key);
			var newValue = payloadExpression.access(source, context);
			context.userCache.put(key, newValue);
			return isSwap ? oldValue : newValue;
		}
	}

	private static class SelectInstruction extends ProcessingInstruction
	{
		private final int resultLimit;
		private final CerberusLogic.Selector selector;

		private SelectInstruction(int resultLimit, CerberusLogic.Selector selector)
		{
			this.resultLimit = resultLimit;
			this.selector = selector;
		}

		@SuppressWarnings({"MethodWithTooManyParameters", "unused"})
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			checkOpeningParenthesis(name, delimiter);
			var resultLimit = 0;
			CerberusLogic.Selector selector = null;
			var start = position.getIndex();
			var buffer = new StringBuilder();
			delimiter = readInput(expression, position, ",|\\)", true, buffer);
			var option = buffer.toString();
			if (option.matches("\\d+"))
			{
				resultLimit = Integer.parseInt(option);
			}
			else
			{
				position.setIndex(start);
				delimiter = ",";
			}
			if (",".equals(delimiter))
			{
				buffer = new StringBuilder();
				delimiter = readInputSyntaxAware(expression, position, "\\)", buffer);
				selector = CerberusLogic.compileSelector(buffer.toString());
			}
			checkClosingParenthesis(name, delimiter);
			result.add(new SelectInstruction(resultLimit, selector));
			return close(expression, start - name.length() - 2, position, endMarkers);
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			var result = new ArrayList<>();
			var input = asIterable(source).iterator();
			while (input.hasNext())
			{
				var element = input.next();
				if ((selector == null) || selector.matches(context.derive(element)))
				{
					result.add(element);
					if ((resultLimit > 0) && (result.size() >= resultLimit))
					{
						if (input instanceof CloseableIterator)
						{
							((CloseableIterator<?>) input).close();
						}
						if (resultLimit == 1)
						{
							return element;
						}
						break;
					}
				}
			}
			if (result.isEmpty())
			{
				result = null;
			}
			return (resultLimit == 1) ? null : result;
		}
	}

	/**
	 * This processing instruction matches data against a list of regular
	 * expressions and upon first match returns the result of evaluating the
	 * associated expression.
	 */
	private static class SwitchInstruction extends ProcessingInstruction
	{
		private final Accessor selector;
		private final List<Accessor> workers;

		private SwitchInstruction(Accessor selector, List<Accessor> workers)
		{
			this.selector = selector;
			this.workers = workers;
		}

		@SuppressWarnings({"MethodWithTooManyParameters", "unchecked"})
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			checkOpeningParenthesis(name, delimiter);
			var start = position.getIndex() - name.length() - 2;
			Accessor selector = null;
			var accessors = new ArrayList<Accessor>();
			delimiter = CompositeAccessor.parseAll(expression, position, ",", "\\)", null, macroLibrary, accessors);
			if (!accessors.isEmpty() && !accessors.get(0).hasKey())
			{
				selector = accessors.remove(0);
			}
			if (accessors.isEmpty())
			{
				throw new RuntimeException(String.format("%s instruction requires at minimum one case clause", name));
			}
			checkClosingParenthesis(name, delimiter);
			var workers = accessors.iterator();
			while (workers.hasNext())
			{
				var worker = workers.next();
				var key = worker.getKey();
				if (key instanceof List)
				{
					// reassemble the regexp that was split on commas
					key = String.join(",", (List<String>) key);
				}
				if (key != null)
				{
					worker.setKey(Pattern.compile((String) key));
				}
				else if (workers.hasNext())
				{
					throw new RuntimeException(String.format("Default %s case needs to be in the last position: %s", name, worker.getExpression()));
				}
			}
			result.add(new SwitchInstruction(selector, accessors));
			return close(expression, start, position, endMarkers);
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			var value = unwrap((selector == null) ? source : selector.access(source, context));
			var discriminant = (value == null) ? null : value.toString();
			for (var worker: workers)
			{
				var pattern = (Pattern) worker.getKey();
				if (pattern != null)
				{
					if ((discriminant != null) && pattern.matcher(discriminant).find())
					{
						return worker.access(source, context);
					}
				}
				else
				{
					return worker.access(source, context);
				}
			}
			return null;
		}
	}

	/**
	 * This processing instruction serves as a debugging aid for parent hierarchies.
	 * It formats the input object, <tt>this</tt> and all parent objects using either
	 * the provided formatting template or toString() and concatenates the representations
	 * into a String using either the provided glue String or newline.
	 */
	private static class InspectInstruction extends ProcessingInstruction
	{
		private final TemplateSubstitution formatter;
		private final String glue;

		private InspectInstruction(TemplateSubstitution formatter, String glue)
		{
			this.formatter = formatter;
			this.glue = glue;
		}

		@SuppressWarnings("MethodWithTooManyParameters")
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			if ("(".equals(delimiter))
			{
				var start = position.getIndex();
				var accessors = new ArrayList<Accessor>();
				delimiter = TemplateSubstitution.parse(expression, position, ",|\\)", macroLibrary, accessors);
				if (!accessors.get(0).getExpression().contains("${") && !accessors.get(0).getExpression().contains("$\""))
				{
					accessors.set(0, null);
					position.setIndex(start);
				}
				var glue = "\n";
				if ((position.getIndex() == start) || ",".equals(delimiter))
				{
					var buffer = new StringBuilder();
					delimiter = readInput(expression, position, "\\)", false, buffer);
					glue = buffer.toString();
				}
				checkClosingParenthesis(name, delimiter);
				delimiter = close(expression, start - name.length() - 2, position, endMarkers);
				result.add(new InspectInstruction((TemplateSubstitution) accessors.get(0), glue));
			}
			else
			{
				result.add(new InspectInstruction(null, "\n"));
			}
			return delimiter;
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			var result = new StringBuilder();
			result.append(format(source, context));
			if ((source != context) && (source != context.payload))
			{
				result.append(glue);
				result.append("@ ");
				result.append(format(context, context));
			}
			var current = (source instanceof Proxy) ? (Proxy<?>) source : context;
			while (((current = current.parent) != null) && (current.payload != null))
			{
				result.append(glue);
				result.append("^ ");
				result.append(format(current, current));
			}
			return result.toString();
		}

		private String format(Object source, Proxy<?> context)
		{
			if (formatter != null)
			{
				return formatter.access(source, context).toString();
			}
			else
			{
				var object = unwrap(source);
				return (object == null) ? "null" : object.toString();
			}
		}
	}

	/**
	 * This processing instruction pretty-prints a Map.
	 */
	private static class PrettyPrintMapConversion extends ProcessingInstruction
	{
		private final boolean renderFrame;

		private PrettyPrintMapConversion(boolean renderFrame)
		{
			this.renderFrame = renderFrame;
		}

		@SuppressWarnings({"MethodWithTooManyParameters", "unused"})
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			var renderFrame = false;
			if ("(".equals(delimiter))
			{
				var buffer = new StringBuilder();
				var start = position.getIndex() - name.length() - 2;
				checkClosingParenthesis(name, readInput(expression, position, "\\)", true, buffer));
				delimiter = close(expression, start, position, endMarkers);
				renderFrame = Boolean.parseBoolean(buffer.toString());
			}
			result.add(new PrettyPrintMapConversion(renderFrame));
			return delimiter;
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			return IndexedData.pprint(unwrap(source), -1, renderFrame);
		}
	}

	/**
	 * This processing instruction converts an Object to XML.
	 */
	private static class PrintToXmlConversion extends ProcessingInstruction
	{
		@Override
		Object access(Object source, Proxy<?> context)
		{
			return XMLObjectFactory.getInstance().toXml(unwrap(source), false);
		}
	}

	/**
	 * This processing instruction creates an object from its XML.
	 */
	private static class ParseXmlConversion extends ProcessingInstruction
	{
		@Override
		Object access(Object source, Proxy<?> context)
		{
			return XMLObjectFactory.getInstance().parseXml(context.resolver, unwrap(source), false);
		}
	}

	/**
	 * This processing instruction executes a String method.
	 */
	private static class StringCaseConversion extends ProcessingInstruction
	{
		private final Function<String, String> worker;

		private StringCaseConversion(String name)
		{
			this.worker = switch (name)
			{
				case "upcase" -> String::toUpperCase;
				case "downcase" -> String::toLowerCase;
				default -> throw new RuntimeException("Invalid case conversion: " + name);
			};
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			var input = unwrap(source);
			return (input == null) ? null : worker.apply(input.toString());
		}
	}

	/**
	 * This processing instruction returns the object's size if applicable.
	 */
	private static class SizeInstruction extends ProcessingInstruction
	{
		@SuppressWarnings("unchecked")
		@Override
		Object access(Object source, Proxy<?> context)
		{
			var input = unwrap(source);
			if (input instanceof Collection)
			{
				var value = (Collection<Object>) input;
				return value.size();
			}
			else if (input instanceof Map)
			{
				var value = (Map<Object, Object>) input;
				return value.size();
			}
			else if (input instanceof String)
			{
				var value = (String) input;
				return value.length();
			}
			else if (input instanceof Iterable)
			{
				var result = 0;
				var value = (Iterable<Object>) input;
				for (var ignored: value)
				{
					++result;
				}
				return result;
			}
			else
			{
				return null;
			}
		}
	}

	/**
	 * This processing instruction generates Date values in various ways.
	 */
	private static class DateInstruction extends ProcessingInstruction
	{
		private static final String TIMESTAMPFORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS";
		private final boolean useCurrentTime;
		private final Date date;
		private final String format;
		private final int offset;
		private final char unit;

		private DateInstruction(boolean useCurrentTime, Date date, String format, int offset, char unit)
		{
			this.useCurrentTime = useCurrentTime;
			this.date = date;
			this.format = format;
			this.offset = offset;
			this.unit = unit;
		}

		@SuppressWarnings({"MethodWithTooManyParameters", "unused"})
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			boolean useCurrentTime = ("now".equals(name));
			if ("(".equals(delimiter))
			{
				var buffer = new StringBuilder();
				var start = position.getIndex() - name.length() - 2;
				checkClosingParenthesis(name, readInput(expression, position, "\\)", false, buffer));
				delimiter = close(expression, start, position, endMarkers);
				var argument = buffer.toString();
				if (argument.isEmpty())
				{
					// Now (alternative syntax)
					if (!useCurrentTime)
					{
						throw new RuntimeException("Missing argument for " + name);
					}
					result.add(new DateInstruction(true, null, null, 0, (char) 0));
				}
				else if (argument.matches("[-+]\\d+[YMWwdhms]"))
				{
					// Calendar calculation with truncate on offset unit
					var offset = Integer.parseInt(argument.substring(0, argument.length() - 1));
					result.add(new DateInstruction(useCurrentTime, null, null, offset, argument.charAt(argument.length() - 1)));
				}
				else if (!useCurrentTime && Character.isDigit(argument.charAt(0)))
				{
					// Date literal
					var patternLength = argument.length();
					if (patternLength > 10)
					{
						patternLength += 2;
					}
					var parser = new SimpleDateFormat(TIMESTAMPFORMAT.substring(0, Math.min(patternLength, TIMESTAMPFORMAT.length())));
					var dateValue = parser.parse(argument, new ParsePosition(0));
					if ((dateValue == null) || !argument.equals(parser.format(dateValue)))
					{
						throw new RuntimeException("Invalid date literal: " + argument);
					}
					result.add(new DateInstruction(false, dateValue, null, 0, (char) 0));
				}
				else if (!useCurrentTime)
				{
					// Date parser
					result.add(new DateInstruction(false, null, argument, 0, (char) 0));
				}
				else
				{
					throw new RuntimeException(String.format("Invalid argument for %s: %s", name, argument));
				}
			}
			else if (useCurrentTime)
			{
				// Now
				result.add(new DateInstruction(true, null, null, 0, (char) 0));
			}
			else
			{
				throw new RuntimeException("Missing argument for " + name);
			}
			return delimiter;
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			if (date != null)
			{
				return date;
			}
			source = unwrap(source);
			if (format != null)
			{
				if (source == null)
				{
					return null;
				}
				var inputString = source.toString();
				if (format.equals("TQ") || format.equals("tQ") || format.equals("Ts") || format.equals("ts"))
				{
					if (format.endsWith("s"))
					{
						inputString = inputString + "000";
					}
					try
					{
						return new Date(Long.parseLong(inputString));
					}
					catch (NumberFormatException e)
					{
						return null;
					}
				}
				else
				{
					var parser = new SimpleDateFormat(format);
					try
					{
						return parser.parse(inputString);
					}
					catch (ParseException e)
					{
						return null;
					}
				}
			}
			else
			{
				var base = useCurrentTime ? new Date() : (Date) source;
				if ((base == null) || (unit == 0))
				{
					return base;
				}
				return addOffset(base, offset, unit);
			}
		}

		/**
		 * Truncate <tt>Date</tt> value to specified time unit and add specified offset
		 */
		private static Date addOffset(Date base, int offset, char unit)
		{
			var calendar = new GregorianCalendar();
			calendar.setTime(base);
			calendar.set(Calendar.MILLISECOND, 0);
			if (unit == 's')
			{
				calendar.add(Calendar.SECOND, offset);
			}
			else
			{
				calendar.set(Calendar.SECOND, 0);
				if (unit == 'm')
				{
					calendar.add(Calendar.MINUTE, offset);
				}
				else
				{
					calendar.set(Calendar.MINUTE, 0);
					if (unit == 'h')
					{
						calendar.add(Calendar.HOUR_OF_DAY, offset);
					}
					else
					{
						calendar.set(Calendar.HOUR_OF_DAY, 0);
						if (unit == 'd')
						{
							calendar.add(Calendar.DAY_OF_YEAR, offset);
						}
						else if ((unit == 'W') || (unit == 'w'))
						{
							// w = week start on Sunday, W = week start on Monday
							calendar.add(Calendar.DAY_OF_YEAR, ((unit == 'W') ? 2 : 1) - calendar.get(Calendar.DAY_OF_WEEK));
							calendar.add(Calendar.DAY_OF_YEAR, 7 * offset);
						}
						else if (unit == 'M')
						{
							calendar.set(Calendar.DAY_OF_MONTH, 1);
							calendar.add(Calendar.MONTH, offset);
						}
						else if (unit == 'Y')
						{
							calendar.set(Calendar.DAY_OF_YEAR, 1);
							calendar.add(Calendar.YEAR, offset);
						}
						else
						{
							throw new RuntimeException(String.format("Invalid calendar offset unit: %c", unit));
						}
					}
				}
			}
			return calendar.getTime();
		}
	}

	/**
	 * This processing instruction creates an HTML link
	 */
	private static class LinkInstruction extends ProcessingInstruction
	{
		private final TemplateSubstitution urlFormatter;
		private final TemplateSubstitution textFormatter;
		private final TemplateSubstitution hintFormatter;
		private final TemplateSubstitution styleFormatter;

		private LinkInstruction(List<Accessor> accessors)
		{
			this.urlFormatter = ((TemplateSubstitution) accessors.get(0)).setResultType(TemplateSubstitution.ResultType.URL);
			this.textFormatter = ((TemplateSubstitution) accessors.get(1)).setResultType(TemplateSubstitution.ResultType.HTML);
			this.hintFormatter = (accessors.size() < 3) ? null : ((TemplateSubstitution) accessors.get(2)).setResultType(TemplateSubstitution.ResultType.HTML);
			this.styleFormatter = (accessors.size() < 4) ? null : ((TemplateSubstitution) accessors.get(3));
		}

		@SuppressWarnings("MethodWithTooManyParameters")
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			checkOpeningParenthesis(name, delimiter);
			var start = position.getIndex() - name.length() - 2;
			List<Accessor> accessors = new ArrayList<>();
			delimiter = TemplateSubstitution.parseAll(expression, position, ",", "\\)", false, macroLibrary, accessors);
			if (accessors.size() < 2)
			{
				throw new RuntimeException(String.format("Not enough parameters for %s instruction", name));
			}
			checkClosingParenthesis(name, delimiter);
			result.add(new LinkInstruction(accessors));
			return close(expression, start, position, endMarkers);
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			var href = (String) urlFormatter.access(source, context);
			if (!"#".equals(href) && !"-".equals(href))
			{
				var baseurl = (context.environment == null) ? null : (String) context.environment.get("baseurl");
				if (baseurl != null)
				{
					try
					{
						href = new URL(new URL(baseurl), href).toString();
					}
					catch (MalformedURLException e)
					{
						throw new RuntimeException("Invalid URL", e);
					}
				}
			}
			var textContainer = (HtmlContainer) textFormatter.access(source, context);
			var text = textContainer.toString();
			var hint = (hintFormatter == null) ? "" : hintFormatter.access(source, context).toString();
			var style = (styleFormatter == null) ? "" : styleFormatter.access(source, context).toString();
			if (!style.isEmpty())
			{
				if (!style.contains(":"))
				{
					style = String.format("color: %s;", style);
				}
				style = String.format(" style=\"%s\"", style);
			}
			if (text.isEmpty())
			{
				text = "⬤";
			}
			var checkboxId = String.format("%d,%d", System.currentTimeMillis(), new Random().nextInt());
			var result = switch (href)
			{
				case "#" -> String.format("<span%s>%s</span>", style, text);
				case "-" -> String.format("<label%s for=\"%s\">%s</label>", style, checkboxId, text);
				default -> String.format("<a%s href=\"%s\" target=\"_blank\">%s</a>", style, href, text);
			};
			if (!hint.isEmpty())
			{
				var toggleControl = "-".equals(href) ? "" : String.format("<label%s for=\"%s\"></label>", style, checkboxId);
				result = String.format(
						"<span class=\"pinnable-tooltip-container\">%s<input type=\"checkbox\" id=\"%s\">%s<div class=\"pinnable-tooltip-content\">%s</div></span>",
						result, checkboxId, toggleControl, hint);
			}
			return new HtmlContainer(result, textContainer.getNumberValue());
		}
	}

	/**
	 * This processing instruction transforms a number into a color value
	 */
	private static class HeatInstruction extends ProcessingInstruction
	{
		private final ArrayList<AbstractMap.SimpleEntry<Integer, int[]>> colorpoints;

		private HeatInstruction(ArrayList<AbstractMap.SimpleEntry<Integer, int[]>> colorpoints) {
			this.colorpoints = colorpoints;
		}

		@SuppressWarnings({"MethodWithTooManyParameters", "unused"})
		static String finalize(String name, String delimiter, StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			var colorpoints = new ArrayList<AbstractMap.SimpleEntry<Integer, int[]>>();
			if ("(".equals(delimiter))
			{
				var parser = Pattern.compile("#([0-9a-fA-F]{2})([0-9a-fA-F]{2})([0-9a-fA-F]{2})(=[0-9]+)?");
				var start = position.getIndex() - name.length() - 2;
				var level = 0;
				delimiter = ",";
				while (",".equals(delimiter))
				{
					var buffer = new StringBuilder();
					delimiter = readInput(expression, position, ",|\\)", true, buffer);
					var matcher = parser.matcher(buffer.toString());
					if (!matcher.matches())
					{
						throw new RuntimeException(String.format("Invalid colorpoint specification: %s", buffer));
					}
					var colors = new int[3];
					for (var group = 0; group < 3; ++group)
					{
						colors[group] = Integer.parseInt(matcher.group(group + 1), 16);
					}
					var levelOverride = matcher.group(4);
					level += (levelOverride == null) ? ((int) Math.signum(colorpoints.size())) * 100 : Integer.parseInt(levelOverride.substring(1));
					colorpoints.add(new AbstractMap.SimpleEntry<>(level, colors));
				}
				checkClosingParenthesis(name, delimiter);
				delimiter = close(expression, start, position, endMarkers);
			}
			if (colorpoints.isEmpty())
			{
				colorpoints.add(new AbstractMap.SimpleEntry<>(0, new int[]{0, 0, 255}));
			}
			if (colorpoints.size() < 2)
			{
				colorpoints.add(new AbstractMap.SimpleEntry<>(100, new int[]{255, 0, 0}));
			}
			result.add(new HeatInstruction(colorpoints));
			return delimiter;
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			source = unwrap(source);
			if (source == null)
			{
				return null;
			}
			var value = (source instanceof Number) ? ((Number) source).doubleValue() : Double.parseDouble(source.toString());
			AbstractMap.SimpleEntry<Integer, int[]> lower = null;
			AbstractMap.SimpleEntry<Integer, int[]> upper = null;
			for (var colorpoint : colorpoints)
			{
				if (value >= colorpoint.getKey())
				{
					lower = colorpoint;
				}
				if (value <= colorpoint.getKey() && (upper == null))
				{
					upper = colorpoint;
				}
			}
			if (lower == null)
			{
				return "#808080";
			}
			if (upper == null)
			{
				return "#c0c0c0";
			}
			var result = new StringBuilder("#");
			var ratio = (lower == upper) ? 0 : (value - lower.getKey()) / (upper.getKey() - lower.getKey());
			for (var index = 0; index < 3; ++index)
			{
				var interpolated = lower.getValue()[index] + ratio * (upper.getValue()[index] - lower.getValue()[index]);
				result.append(String.format("%02x", (int) interpolated));
			}
			return result.toString();
		}
	}

	/**
	 * Accessors of this type represent a formatting operation, creating a String
	 * representation of their input object using a format template with substitution
	 * placeholders.
	 */
	private static class TemplateSubstitution extends Accessor
	{
		public enum ResultType {PLAIN, HTML, URL}

		private static class Part
		{
			private final String format;
			private final String formatStripped;
			private final Accessor accessor;
			private final String defaultValue;
			private final boolean doQuoting;

			private Part(String format, Accessor accessor, String defaultValue, boolean doQuoting)
			{
				this.format = format;
				this.formatStripped = (format == null) ? null : format.substring(1);
				this.accessor = accessor;
				this.defaultValue = defaultValue;
				this.doQuoting = doQuoting;
			}

			@SuppressWarnings("unchecked")
			private Object render(StringBuilder output, Object source, Proxy<?> context, ResultType resultType)
			{
				if (accessor == null)
				{
					output.append(format);
					return null;
				}
				String result;
				var value = unwrap(accessor.access(source, context));
				if (value != null)
				{
					if (format != null)
					{
						if ((value instanceof Date) && !(format.contains("%t") || format.contains("%T")))
						{
							result = new SimpleDateFormat(formatStripped).format(value);
						}
						else
						{
							result = String.format(formatStripped.contains("%") ? formatStripped : format, value);
						}
					}
					else
					{
						if (value instanceof Date)
						{
							if (doQuoting)
							{
								// special Date formatting for Sailpoint Filters as DATE$milliseconds
								output.append(String.format("DATE$%tQ", value));
								return value;
							}
							else
							{
								result = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(value);
							}
						}
						else if ((value instanceof Iterable) && doQuoting)
						{
							// special list formatting for Sailpoint "in" Filters as { "...", ... }
							var input = ((Iterable<Object>) value).iterator();
							output.append("{");
							while (input.hasNext()) {
								var element = input.next();
								output.append("\"");
								output.append((element == null) ? "null" : element.toString().replace("\\", "\\\\").replace("\"", "\\\""));
								output.append("\"");
								if (input.hasNext())
								{
									output.append(", ");
								}
							}
							output.append("}");
							return value;
						}
						else
						{
							result = value.toString();
						}
					}
					if (resultType == ResultType.URL)
					{
						result = URLEncoder.encode(result, StandardCharsets.UTF_8);
					}
					else if (resultType == ResultType.HTML)
					{
						if (doQuoting)
						{
							result = String.format("\"%s\"", result.replace("&", "&amp;").replace("\"", "&quot;"));
						}
						else
						{
							result = (value instanceof HtmlContainer) ? result : StringEscapeUtils.escapeHtml(result);
						}
					}
					else if (doQuoting)
					{
						result = String.format("\"%s\"", result.replace("\\", "\\\\").replace("\"", "\\\""));
					}
				}
				else
				{
					result = defaultValue;
				}
				if ((result == null) && doQuoting)
				{
					throw new RuntimeException(String.format("Null value in quoting placeholder $\"%s\"", accessor.getExpression()));
				}
				output.append(result);
				return value;
			}
		}

		private final List<Part> parts;
		private ResultType resultType = ResultType.PLAIN;

		private TemplateSubstitution(List<Part> parts)
		{
			this.parts = parts;
		}

		public boolean isEmpty()
		{
			return parts.isEmpty();
		}

		public TemplateSubstitution setResultType(ResultType resultType)
		{
			this.resultType = resultType;
			return this;
		}

		/**
		 * Documentation see {@link Accessor#parse(StringBuilder, ParsePosition, String, Map, List)}
		 */
		static String parse(StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, List<Accessor> result)
		{
			var parts = new ArrayList<Part>();
			var start = position.getIndex();
			var delimiter = parseSyntaxAware(expression, position, endMarkers, macroLibrary, parts);
			var accessor = (Accessor) new TemplateSubstitution(parts);
			accessor.setExpression(expression.substring(start, position.getIndex() - ((delimiter == null) ? 0 : delimiter.length())));
			result.add(accessor);
			return delimiter;
		}

		/**
		 * Parse template code until the end marker appears outside any parenthesized
		 * construct or string literal
		 */
		static String parseSyntaxAware(StringBuilder expression, ParsePosition position, String endMarkers, Map<String, String> macroLibrary, ArrayList<Part> parts)
		{
			var breakPatterns = combineRE("\\(|\"|\\$.", endMarkers);
			while (true) {
				var buffer = new StringBuilder();
				var delimiter = readInput(expression, position, breakPatterns, false, buffer);
				if (!buffer.isEmpty())
				{
					addTextFragment(parts, buffer.toString());
				}
				var start = position.getIndex();
				if ("(".equals(delimiter))
				{
					addTextFragment(parts, delimiter);
					delimiter = parseSyntaxAware(expression, position, "\\)", macroLibrary, parts);
					if (delimiter == null)
					{
						throw new RuntimeException("Unclosed parenthesized construct: " + expression.substring(start - 1));
					}
					addTextFragment(parts, delimiter);
				}
				else if ("\"".equals(delimiter))
				{
					addTextFragment(parts, expression.substring(start - 1, findClosingQuote(expression, position, start - 1)));
				}
				else if ("${".equals(delimiter) || "$\"".equals(delimiter))
				{
					var doQuoting = "$\"".equals(delimiter);
					var commaOrEnd = doQuoting ? ",|\"" : ",|\\}";
					StringBuilder format = null;
					StringBuilder defaultValue = null;
					if (startsWith(expression, position,"%"))
					{
						format = new StringBuilder();
						delimiter = readInput(expression, position, commaOrEnd, false, format);
						if (delimiter == null)
						{
							throw new RuntimeException("Format specification not properly terminated");
						}
					}
					else
					{
						delimiter = ",";
					}
					var accessors = new ArrayList<Accessor>();
					if (",".equals(delimiter))
					{
						delimiter = CompositeAccessor.parse(expression, position, commaOrEnd, macroLibrary, accessors);
					}
					if (",".equals(delimiter))
					{
						defaultValue = new StringBuilder();
						delimiter = readInput(expression, position, doQuoting ? "\"" : "\\}", false, defaultValue);
					}
					if (delimiter == null)
					{
						throw new RuntimeException("Substitution placeholder not properly terminated");
					}
					addSubstitution(parts, format, accessors.isEmpty() ? new AccessorChain(new ArrayList<>()) : accessors.get(0), defaultValue, doQuoting);
				}
				else if ((delimiter != null) && delimiter.startsWith("$"))
				{
					position.setIndex(start - 2);
					expandMacro(expression, position, macroLibrary);
				}
				else
				{
					// end of template reached
					return delimiter;
				}
			}
		}

		private static void addTextFragment(ArrayList<Part> parts, String text)
		{
			var currentPart = parts.isEmpty() ? null : parts.get(parts.size() - 1);
			if ((currentPart != null) && (currentPart.accessor == null))
			{
				parts.set(parts.size() - 1, new Part(currentPart.format + text, null, null, false));
			}
			else
			{
				parts.add(new Part(text, null, null, false));
			}
		}

		/**
		 * Read multiple templates from <tt>expression</tt>, accepting assignment syntax
		 * as well, and append the compiled accessors to <tt>result</tt>. Upon entry, <tt>position</tt>
		 * points to the first character of the first template specification. Upon exit, <tt>position</tt>
		 * points behind the end marker found or behind the end of input. The end marker is returned.
		 *
		 * @param expression          the expression under parsing
		 * @param position            the current parse position in it, will be updated
		 * @param delimiterPattern    the regular expression identifying the delimiter between templates
		 * @param endMarkers          the regular expression signaling the end of the construct to parse
		 * @param useAssignmentSyntax if null, assignment syntax is accepted but not required, otherwise specifies if assignment syntax is used or not
		 * @param result              the accessors resulting from the parse will be added to this list
		 * @return the delimiter matched by <tt>endMarkers</tt> or <tt>null</tt> when EOI was reached
		 */
		@SuppressWarnings("SameParameterValue")
		static String parseAll(StringBuilder expression, ParsePosition position, String delimiterPattern, String endMarkers, Boolean useAssignmentSyntax, Map<String, String> macroLibrary, List<Accessor> result)
		{
			var endPatterns = combineRE(delimiterPattern, endMarkers);
			String delimiter;
			while (true)
			{
				if (expandMacro(expression, position, macroLibrary))
				{
					continue;
				}
				var start = position.getIndex();
				var key = ((useAssignmentSyntax == null) || useAssignmentSyntax) ? readAssignmentKey(expression, position, combineRE("\\$", endPatterns)) : null;
				if (Boolean.TRUE.equals(useAssignmentSyntax) && (key == null))
				{
					throw new RuntimeException("Missing assignment before template: " + expression.substring(start));
				}
				delimiter = parse(expression, position, endPatterns, macroLibrary, result);
				if (((useAssignmentSyntax == null) || useAssignmentSyntax) && (start == position.getIndex() - ((delimiter == null) ? 0 : delimiter.length())))
				{
					throw new RuntimeException(String.format("Empty template definition at position %d: %s", start, expression.substring(start)));
				}
				if (key != null)
				{
					result.get(result.size() - 1).setKey((key.size() == 1) ? key.get(0) : key);
				}
				if ((delimiter == null) || ((endMarkers != null) && delimiter.matches(endMarkers)))
				{
					break;
				}
			}
			return delimiter;
		}

		private static void addSubstitution(ArrayList<Part> parts, StringBuilder format, Accessor accessor, StringBuilder defaultValue, boolean doQuoting)
		{
			parts.add(new Part(
					(format == null) ? null : format.toString(),
					accessor,
					(defaultValue == null) ? null : defaultValue.toString(),
					doQuoting
			));
		}

		@Override
		Object access(Object source, Proxy<?> context)
		{
			Object value = null;
			var builder = new StringBuilder();
			for (var part: parts)
			{
				value = part.render(builder, source, context, resultType);
			}
			if (value instanceof HtmlContainer)
			{
				value = ((HtmlContainer) value).getNumberValue();
			}
			if ((parts.size() != 1) || !(value instanceof Number))
			{
				value = null;
			}
			String result = builder.toString();
			return (resultType == ResultType.HTML) ? new HtmlContainer(result, (Number) value) : result;
		}
	}

	/**
	 * Objects of this class represent Strings that are guaranteed to be valid HTML,
	 * so they can be included into HTML without escaping.
	 */
	public static class HtmlContainer implements Comparable<HtmlContainer>
	{
		private final String html;
		private final Number numberValue;

		public HtmlContainer(String html, Number numberValue)
		{
			this.html = html;
			this.numberValue = numberValue;
		}

		public boolean isNumeric()
		{
			return (numberValue != null);
		}

		public Number getNumberValue()
		{
			return numberValue;
		}

		@Override
		public String toString()
		{
			return html;
		}

		@Override
		public int compareTo(@NotNull HtmlContainer other)
		{
			return html.compareTo(other.html);
		}

		@Override
		public boolean equals(Object other)
		{
			return (other instanceof HtmlContainer) && html.equals(((HtmlContainer) other).html);
		}

		@Override
		public int hashCode()
		{
			return html.hashCode();
		}
	}

	private OrionQL()
	{}

	/**
	 * Guess if <tt>input</tt> is a formatting template by checking for a placeholder
	 * starting at the first or second character (resolving any macros necessary)
	 *
	 * @param input         the input string to check
	 * @param macroLibrary  a Map that links macro names to their expansions
	 * @return true if <tt>input</tt> appears to be a formatting template
	 */
	public static boolean guessInputType(String input, Map<String, String> macroLibrary)
	{
		var buffer = new StringBuilder(input);
		var dollarPosition = buffer.indexOf("$");
		while ((dollarPosition >= 0) && (dollarPosition < 2))
		{
			if (!Accessor.expandMacro(buffer, new ParsePosition(0), macroLibrary))
			{
				break;
			}
			dollarPosition = buffer.indexOf("$");
		}
		return (dollarPosition >= 0) && (dollarPosition < 2);
	}

	/**
	 * Variant of {@link #compile(CharSequence, boolean, Map)} that does not use a macro library
	 */
	public static Accessor compile(CharSequence input, boolean isTemplate)
	{
		return compile(input, isTemplate, Collections.emptyMap());
	}

	/**
	 * Compile an OrionQL expression or template string. Compiling an empty string as
	 * template will return an accessor that returns the empty string, and compiling
	 * it as expression will return an accessor that returns its input.
	 *
	 * @param input         the expression or template string to compile; if given
	 *                      as StringBuilder, it will be updated by the macro expansions
	 * @param isTemplate    if <tt>true</tt>, <tt>input</tt> is compiled as template string, else as expression
	 * @param macroLibrary  a Map that links macro names to their expansions
	 * @return {@link Accessor} representing the compiled input
	 */
	public static Accessor compile(CharSequence input, boolean isTemplate, Map<String, String> macroLibrary)
	{
		var buffer = (input instanceof StringBuilder) ? (StringBuilder) input : new StringBuilder(input);
		List<Accessor> accessors = new ArrayList<>();
		if (isTemplate)
		{
			TemplateSubstitution.parse(buffer, new ParsePosition(0), null, macroLibrary, accessors);
		}
		else
		{
			CompositeAccessor.parse(buffer, new ParsePosition(0), null, macroLibrary, accessors);
		}
		return accessors.get(0);
	}

	/**
	 * Variant of {@link #compileAll(CharSequence, String, Boolean, boolean, Map)} that does not use a macro library
	 */
	public static List<Accessor> compileAll(CharSequence input, String delimiterPattern, Boolean useAssignmentSyntax, boolean isTemplates)
	{
		return compileAll(input, delimiterPattern, useAssignmentSyntax, isTemplates, Collections.emptyMap());
	}

	/**
	 * Compile all OrionQL expressions or template strings contained in the
	 * <tt>input</tt> string, accepting assignment syntax as well
	 *
	 * @param input               the text containing the expressions or template strings to compile; if given
	 *                            as StringBuilder, it will be updated by the macro expansions
	 * @param delimiterPattern    the regular expression identifying the delimiter between expressions
	 * @param useAssignmentSyntax if null, assignment syntax is accepted but not required, otherwise specifies if assignment syntax is used or not
	 * @param isTemplates         if <tt>true</tt>, <tt>input</tt> is compiled as template strings, else as expressions
	 * @param macroLibrary        a Map that links macro names to their expansions
	 * @return List of {@link Accessor} representing the compiled input
	 */
	public static List<Accessor> compileAll(CharSequence input, String delimiterPattern, Boolean useAssignmentSyntax, boolean isTemplates, Map<String, String> macroLibrary)
	{
		var buffer = (input instanceof StringBuilder) ? (StringBuilder) input : new StringBuilder(input);
		List<Accessor> accessors = new ArrayList<>();
		if (isTemplates)
		{
			TemplateSubstitution.parseAll(buffer, new ParsePosition(0), delimiterPattern, null, useAssignmentSyntax, macroLibrary, accessors);
		}
		else
		{
			CompositeAccessor.parseAll(buffer, new ParsePosition(0), delimiterPattern, null, useAssignmentSyntax, macroLibrary, accessors);
		}
		return accessors;
	}

	/**
	 * Variant of {@link #wrap(T, SailPointContext, Map, Map)} that does not use a macro library nor an environment map
	 */
	public static <T> Proxy<T> wrap(T payload, SailPointContext resolver)
	{
		return wrap(payload, resolver, null, null);
	}

	/**
	 * Wrap a payload object into an {@link Proxy}, so OrionQL expressions can be
	 * evaluated on it
	 *
	 * @param payload       the object to wrap for evaluating expressions on it, can be <tt>null</tt>
	 *                      if the proxy used solely as parent for deriving other proxies
	 * @param resolver      the SailPointContext to use by accessors that need one, can be <tt>null</tt>
	 *                      if no such accessors are to be used
	 * @param macroLibrary  a Map that links macro names to their expansions
	 * @param environment   a Map with environment variables
	 */
	@SuppressWarnings("unchecked")
	public static <T> Proxy<T> wrap(T payload, SailPointContext resolver, Map<String, String> macroLibrary, Map<String,Object> environment)
	{
		if (payload instanceof Proxy)
		{
			return (Proxy<T>) payload;
		}
		return new Proxy<>(null, payload, resolver, macroLibrary, environment);
	}

	/**
	 * Unwrap a possibly wrapped payload object
	 */
	@SuppressWarnings("unchecked")
	protected static <K> K unwrap(Object source)
	{
		// there should never be nested wrappings, but in case...
		while (source instanceof Proxy)
		{
			source = ((Proxy<Object>) source).payload;
		}
		return (K) source;
	}

	/**
	 * Convenience method to evaluate an OrionQL expression on an object
	 *
	 * @param target object to apply expression to
	 * @param expression expression to evaluate
	 * @param resolver SailPointContext, can be null if not needed by the expression
	 * @return result of expression evaluation
	 */
	public static Object evaluate(Object target, String expression, SailPointContext resolver)
	{
		return wrap(target, resolver).access(expression);
	}

	/**
	 * Convenience method to apply an OrionQL formatting template to an object
	 *
	 * @param target object to apply formatting template to
	 * @param template formatting template to render
	 * @param resolver SailPointContext, can be null if not needed by the expression
	 * @return result of template rendering
	 */
	public static Object format(Object target, String template, SailPointContext resolver)
	{
		return wrap(target, resolver).format(template);
	}

	/**
	 * Evaluate multiple expressions, returning the results as a Map
	 *
	 * @param accessors Accessors representing the expressions to evaluate (assignment syntax and list/map decomposition are supported)
	 * @param target object to apply the expressions to
	 * @return Map containing the evaluation results
	 */
	public static Map<String, Object> evaluateAll(List<Accessor> accessors, Proxy<?> target)
	{
		return new ConstructInstruction(accessors).access(target);
	}
}
