package com.six.iam.util;

import org.apache.commons.lang.StringEscapeUtils;
import org.jetbrains.annotations.NotNull;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Map;
import java.util.*;
import java.util.function.*;

/**
 * Objects of this class represent a list of {@link OrionQL} expressions to be used
 * for indexing objects into a potentially multilevel mapping. The provided low
 * and high level functions permit storing, counting and retrieving objects.
 */
public class IndexedData<K, T>
{
	/**
	 * Mapper for converting objects to text. The default implementation
	 * wraps an OrionQL Accessor, but you can subclass it to provide your
	 * own mapper code. Two standard mappers are provided as static members.
	 */
	public static class CellMapper
	{
		private final OrionQL.Accessor worker;

		public CellMapper(OrionQL.Accessor worker)
		{
			this.worker = worker;
		}

		public Object map(Object input)
		{
			return (input == null) ? null : worker.access(OrionQL.wrap(input, null));
		}

		/**
		 * Standard mapper to pretty-print a potentially multilevel mapping
		 */
		public static final CellMapper PPRINT = new CellMapper(OrionQL.compile(":pprint", false));

		/**
		 * Standard mapper to concatenate the elements of an Iterable using newlines
		 */
		public static final CellMapper JOIN = new CellMapper(OrionQL.compile(":join", false));
	}

	private Map<K, Object> index;
	private final List<OrionQL.Accessor> key = new ArrayList<>();
	private Function<OrionQL.Proxy<T>, Object> mapper = null;
	private Supplier<Collection<Object>> bucketConstructor = ArrayList::new;
	private int objectCount = 0;

	/**
	 * Variant of {@link #IndexedData(Map, List, boolean, Map)} that does not use a macro library
	 */
	public IndexedData(Map<K, Object> index, List<String> keyExpressions, boolean isTemplates)
	{
		this(index, keyExpressions, isTemplates, Collections.emptyMap());
	}

	/**
	 * Construct an IndexedData from {@link OrionQL} expressions
	 * @param index the potentially multilevel mapping this object operates on. May be
	 *              shared by multiple instances if needed to enable different access
	 *              strategies to the same data. Unless sorted key order is needed when
	 *              traversing the index, a HashMap will suffice, else use a TreeMap
	 *              and make sure keys are Comparables and never null.
	 * @param keyExpressions the values used for indexing the objects expressed in
	 *                       OrionQL
	 * @param isTemplates    if <tt>true</tt>, the key expressions are expected as OrionQL
	 *                       formatting templates instead of OrionQL expressions
	 * @param macroLibrary   a Map that links OrionQL macro names to their expansions
	 */
	public IndexedData(Map<K, Object> index, List<String> keyExpressions, boolean isTemplates, Map<String, String> macroLibrary)
	{
		this.index = index;
		for (var expression : keyExpressions)
		{
			key.add(OrionQL.compile(expression, isTemplates, macroLibrary));
		}
	}

	/**
	 * Alternative constructor, taking as input precompiled {@link OrionQL} key expressions
	 * and setting a mapper
	 * @param accessors list of precompiled key expressions
	 * @param mapper    mapper function as accepted by {@link #useMapper(Function)}
	 */
	public IndexedData(List<OrionQL.Accessor> accessors, OrionQL.Accessor mapper)
	{
		key.addAll(accessors);
		if (mapper != null)
		{
			this.mapper = mapper::access;
		}
	}

	/**
	 * Specify the Collection type to be used for the buckets holding the objects
	 * added to an index position by giving its constructor. The default bucket type
	 * is <tt>ArrayList</tt>. Returns <tt>this</tt> to allow method chaining.
	 */
	public IndexedData<K, T> useCollection(Supplier<Collection<Object>> bucketConstructor)
	{
		this.bucketConstructor = bucketConstructor;
		return this;
	}

	/**
	 * Specify a mapper function to be used by {@link #add(OrionQL.Proxy)},
	 * {@link #put(OrionQL.Proxy)} and their corresponding <tt>addAll()</tt>
	 * and <tt>putAll()</tt> methods to convert the proxy into the object to be
	 * stored in the mapping. Without a mapper, the proxy itself is stored.
	 */
	public IndexedData<K, T> useMapper(Function<OrionQL.Proxy<T>, Object> mapper)
	{
		this.mapper = mapper;
		return this;
	}

	/**
	 * Return the nesting depth of this IndexedData
	 */
	public int getDepth()
	{
		return key.size();
	}

	/**
	 * Set the mapping to operate upon by this <tt>IndexedData</tt> instance
	 */
	public IndexedData<K, T> set(Map<K, Object> index)
	{
		this.index = index;
		return this;
	}

	/**
	 * Return the mapping this <tt>IndexedData</tt> instance operates upon
	 */
	public Map<K, Object> get()
	{
		return index;
	}

	@SuppressWarnings("unchecked")
	@NotNull
	private K[] makeKey(OrionQL.Proxy<T> object)
	{
		var result = (K[]) new Object[key.size()];
		var position = 0;
		for (var accessor: key)
		{
			result[position++] = accessor.access(object);
		}
		return result;
	}

	/**
	 * Add an object to the bucket at the index position computed for this object. If a mapper
	 * was provided (see {@link #useMapper(Function)}), the result of applying the mapper to the
	 * object is added instead of the object itself.
	 */
	public void add(OrionQL.Proxy<T> object)
	{
		++objectCount;
		add(bucketConstructor, index, (mapper == null) ? object : mapper.apply(object), makeKey(object));
	}

	/**
	 * Increment the object count for the index position of an object
	 */
	public void count(OrionQL.Proxy<T> object)
	{
		++objectCount;
		count(index, makeKey(object));
	}

	/**
	 * <p>Put an object into the index at the index position computed for it. If a mapper
	 * was provided (see {@link #useMapper(Function)}), the result of applying the mapper to the
	 * object is put instead of the object itself.
	 * </p>
	 * <p>Returns the object previously there or <tt>null</tt> if <tt>none</tt>.
	 * </p>
	 */
	@SuppressWarnings("unchecked")
	public <V> V put(OrionQL.Proxy<T> object)
	{
		++objectCount;
		return (V) put(index, (mapper == null) ? object : mapper.apply(object), makeKey(object));
	}

	/**
	 * Analogous to {@link Map#computeIfAbsent(Object, Function)}
	 */
	@SuppressWarnings("unchecked")
	public <V> V computeIfAbsent(OrionQL.Proxy<T> object, Function<K, Object> initializer)
	{
		return (V) computeIfAbsent(index, initializer, makeKey(object));
	}

	/**
	 * Get the object from the index located at the index position computed for <tt>object</tt>
	 */
	public <V> V get(OrionQL.Proxy<T> object)
	{
		return get(index, makeKey(object));
	}

	/**
	 * Get the first leaf object and its nesting level. See {@link IndexedData#peek(Map)}
	 */
	public Map.Entry<Integer, Object> peek()
	{
		return peek(index);
	}

	/**
	 * Add to the index all objects returned by an iterator. Variant for an iterator yielding
	 * objects of type {@link OrionQL.Proxy}.
	 */
	public IndexedData<K, T> addAll(Iterator<OrionQL.Proxy<T>> input)
	{
		while (input.hasNext())
		{
			add(input.next());
		}
		return this;
	}

	/**
	 * Add to the index all objects returned by an iterator. Variant for an iterator
	 * yielding plain objects (it has to be wrapped into an {@link OrionQL.Proxy}
	 * <i>itself</i> for that).
	 */
	public IndexedData<K, T> addAll(OrionQL.Proxy<Iterator<T>> input)
	{
		return doAll(input, this::add);
	}

	/**
	 * Count all objects returned by an iterator according to their computed index
	 * positions. Variant for an iterator yielding objects of type {@link OrionQL.Proxy}.
	 */
	@SuppressWarnings("unused")
	public IndexedData<K, T> countAll(Iterator<OrionQL.Proxy<T>> input)
	{
		while (input.hasNext())
		{
			count(input.next());
		}
		return this;
	}

	/**
	 * Count all objects returned by an iterator according to their computed index
	 * positions. Variant for an iterator yielding plain objects (it has to be wrapped
	 * into an {@link OrionQL.Proxy} <i>itself</i> for that).
	 */
	@SuppressWarnings("UnusedReturnValue")
	public IndexedData<K, T> countAll(OrionQL.Proxy<Iterator<T>> input)
	{
		return doAll(input, this::count);
	}

	/**
	 * Put into the index all objects returned by an iterator. The index position must
	 * be empty (or occupied by <tt>null</tt>), or a key collision will be reported.
	 * Variant for an iterator yielding objects of type {@link OrionQL.Proxy}.
	 */
	public IndexedData<K, T> putAll(Iterator<OrionQL.Proxy<T>> input)
	{
		while (input.hasNext())
		{
			if (put(input.next()) != null)
			{
				throw new RuntimeException("Key collision");
			}
		}
		return this;
	}

	/**
	 * Put into the index all objects returned by an iterator. The index position must
	 * be empty (or occupied by <tt>null</tt>), or a key collision will be reported.
	 * Variant for an iterator yielding plain objects (it has to be wrapped into an
	 * {@link OrionQL.Proxy} <i>itself</i> for that).
	 */
	public IndexedData<K, T> putAll(OrionQL.Proxy<Iterator<T>> input)
	{
		return doAll(input, o -> {
			if (put(o) != null)
			{
				throw new RuntimeException("Key collision");
			}
		});
	}

	/**
	 * Execute <tt>worker</tt> for all objects returned by a wrapped iterator
	 */
	private IndexedData<K, T> doAll(OrionQL.Proxy<Iterator<T>> input, Consumer<OrionQL.Proxy<T>> worker)
	{
		var inputIterator = input.unwrap();
		while (inputIterator.hasNext())
		{
			worker.accept(input.derive(inputIterator.next()));
		}
		return this;
	}

	/**
	 * Return the number of objects that have been stored into this instance
	 * (added, counted or put)
	 */
	public int getObjectCount()
	{
		return objectCount;
	}

	/**
	 * Pretty-print the mapping this <tt>IndexedData</tt> instance operates upon. See {@link IndexedData#pprint(Map, int, boolean)}
	 */
	public String pprint(int labelWidth, boolean renderFrame)
	{
		return pprint(index, labelWidth, renderFrame);
	}

	/**
	 * Write the minimum two levels deep mapping this <tt>IndexedData</tt> instance operates
	 * upon, representing the rows of a matrix, to the given OutputStream as HTML table
	 */
	public void writeHtmlMatrix(OutputStream outputStream, String charset, CellMapper cellMapper, boolean doTotals, boolean rotateHeaders) throws IOException
	{
		writeHtmlMatrix(index, outputStream, charset, cellMapper, doTotals, rotateHeaders);
	}

	/**
	 * Write the minimum numCaptionLevels + 2 levels deep mapping this <tt>IndexedData</tt>
	 * instance operates upon onto the given OutputStream as matrices separated by captions.
	 * If numCaptionLevels == 0, this method is equivalent to
	 * {@link #writeHtmlMatrix(OutputStream, String, CellMapper, boolean, boolean)}.
	 */
	public void writeHtmlMatrices(int numCaptionLevels, OutputStream outputStream, String charset, CellMapper cellMapper, boolean doTotals, boolean rotateHeaders, boolean addNavigation) throws IOException
	{
		writeHtmlMatrices(index, numCaptionLevels, outputStream, charset, cellMapper, doTotals, rotateHeaders, addNavigation);
	}

	/**
	 * Write the data contained in the leaf positions of the mapping this
	 * <tt>IndexedData</tt> instance operates upon as Iterables of Proxies
	 * to the given OutputStream as tables separated by captions.
	 */
	public void writeHtmlTables(OutputStream outputStream, String charset, TabularData writer, boolean addNavigation) throws IOException
	{
		writeHtmlTables(index, getDepth(), outputStream, charset, writer, addNavigation);
	}

	/**
	 * Drill down a map hierarchy down to the leaf map, creating intermediate nodes as necessary
	 *
	 * @param root root of the mapping
	 * @param keys key sequence identifying a position in the map hierarchy. The leaf map is the
	 *             one addressed by all elements but the last (which indexes into the leaf map and
	 *             thus is ignored here).
	 * @param <K>  key type
	 * @return The leaf map addressed by the key sequence
	 * @throws ClassCastException if any key position resolves to the wrong type
	 */
	@SuppressWarnings("unchecked")
	private static <K> Map<K, Object> getLeafNode(Map<K, Object> root, K[] keys)
	{
		var current = root;
		for (var i = 0; i < keys.length - 1; ++i)
		{
			current = (Map<K, Object>) current.computeIfAbsent(keys[i], (Function<Object, Object>) o -> createNode(root));
		}
		return current;
	}

	/**
	 * Try to create a Map of the same type as <tt>prototype</tt>, otherwise HashMap
	 */
	@SuppressWarnings("unchecked")
	@NotNull
	private static <K> Map<K, Object> createNode(Map<K, Object> prototype)
	{
		try
		{
			return prototype.getClass()
					.getDeclaredConstructor()
					.newInstance();
		}
		catch (Exception e)
		{
			return new HashMap<>();
		}
	}

	/**
	 * Add an object to a possibly multilevel mapping at the position identified by
	 * the given key sequence. Every position will hold a collection of all objects added
	 * to the mapping with the same key sequence.
	 *
	 * @param bucketConstructor constructor for the collection object to hold the objects added to an index position
	 * @param root              root of the possibly multilevel mapping receiving the value
	 * @param value             object to put into the mapping at the index position
	 * @param keys              key sequence indexing into the mapping identifying position where to add the object
	 * @param <K>               key type
	 * @param <V>               value type
	 * @throws ClassCastException if any key resolves to the wrong type (node vs. bucket)
	 */
	@SuppressWarnings("unchecked")
	public static <K, V> void add(Supplier<Collection<V>> bucketConstructor, Map<K, Object> root, V value, K... keys)
	{
		var leaf = getLeafNode(root, keys);
		var bucket = (Collection<Object>) leaf.computeIfAbsent(keys[keys.length-1], key -> bucketConstructor.get());
		bucket.add(value);
	}

	/**
	 * Variant of {@link #add(Supplier, Map, Object, Object[])} using <tt>ArrayList::new</tt>
	 * to construct the bucket collections
	 */
	@SafeVarargs
	public static <K, V> void add(Map<K, Object> root, V value, K... keys)
	{
		add(ArrayList::new, root, value, keys);
	}

	/**
	 * Increment the count (initialized with zero) maintained in a possibly multilevel
	 * mapping at the position identified by the given key sequence
	 *
	 * @param root root of the possibly multilevel mapping maintaining the counts
	 * @param keys key sequence identifying the position of the count to be incremented
	 * @param <K>  key type
	 * @throws ClassCastException if any key position resolves to the wrong type
	 */
	@SuppressWarnings("unchecked")
	public static <K> void count(Map<K, Object> root, K... keys)
	{
		var leaf = getLeafNode(root, keys);
		var leafKey = keys[keys.length-1];
		var count = (Integer) leaf.computeIfAbsent(leafKey, key -> 0);
		leaf.put(leafKey, count + 1);
	}

	/**
	 * This method is Map.put() generalized for a multilevel mapping.
	 *
	 * @param root  root of the possibly multilevel mapping receiving the value
	 * @param value object to put into the mapping at the key position
	 * @param keys  key sequence identifying the position where the object is to be put
	 * @param <K>   key type
	 * @param <V>   value type
	 * @return The value previously stored at the put position or null if none
	 * @throws ClassCastException if any key position resolves to the wrong type
	 */
	@SuppressWarnings("unchecked")
	public static <K, V> V put(Map<K, Object> root, V value, K... keys)
	{
		return (V) getLeafNode(root, keys).put(keys[keys.length-1], value);
	}

	/**
	 * This method is Map.computeIfAbsent() generalized for a multilevel mapping.
	 *
	 * @param root        root of the possibly multilevel mapping to work on
	 * @param initializer function to provide the object to initialize empty key
	 *                    positions. This is the mapping function used for the
	 *                    Map.computeIfAbsent() call on the leaf map (lowest level
	 *                    map).
	 * @param keys        key sequence identifying the position to access
	 * @param <K>         key type
	 * @param <V>         value type
	 * @return The existing or computed value associated with the specified key
	 * sequence, or null if the computed value is null
	 * @throws ClassCastException if any key position resolves to the wrong type
	 */
	@SuppressWarnings("unchecked")
	public static <K, V> V computeIfAbsent(Map<K, Object> root, Function<K, V> initializer, K... keys)
	{
		return (V) getLeafNode(root, keys).computeIfAbsent(keys[keys.length-1], initializer);
	}

	/**
	 * This method is Map.get() generalized for a multilevel mapping.
	 *
	 * @param root root of the possibly multilevel mapping to query
	 * @param keys key sequence identifying the position of the value to retrieve
	 * @param <K>  key type
	 * @param <V>  value type
	 * @return The value at the index position or null
	 * @throws ClassCastException if any key position resolves to the wrong type
	 */
	@SuppressWarnings("unchecked")
	public static <K, V> V get(Map<K, Object> root, K... keys)
	{
		Object current = root;
		for (var key: keys)
		{
			var node = (Map<K, Object>) current;
			current = node.get(key);
			if (current == null)
			{
				break;
			}
		}
		return (V) current;
	}

	/**
	 * Drill down into a possibly multilevel mapping and return the first
	 * leaf (that is, <i>non-map</i> value) together with its nesting level
	 *
	 * @param root the possibly multilevel mapping to examine
	 * @return <tt>null</tt> if no leaf is found, otherwise a Map.Entry object with the
	 *   leaf's nesting level in the key (1 = top level) and the leaf in the
	 *   value
	 */
	@SuppressWarnings({"unchecked"})
	public static <K> Map.Entry<Integer, Object> peek(Map<K, Object> root) {
		var stack = new LinkedList<Iterator<Object>>();
		var current = root.values().iterator();
		while (true) {
			if (current.hasNext()) {
				var value = current.next();
				if (value instanceof Map)
				{
					stack.push(current);
					current = ((Map<K, Object>) value).values().iterator();
				}
				else
				{
					return new AbstractMap.SimpleEntry<>(stack.size() + 1, value);
				}
			}
			else
			{
				if (stack.isEmpty())
				{
					return null;
				}
				current = stack.pop();
			}
		}
	}

	/**
	 * Recursively merge the contents of the <tt>source</tt> mapping into <tt>target</tt>
	 * by overwriting the values in <tt>target</tt> with the values at the same key position
	 * in <tt>source</tt> (recursive <tt>putAll()</tt>)
	 */
	@SuppressWarnings("unchecked")
	public static <K> void overlay(Map<K, Object> target, Map<K, Object> source)
	{
		for (var entry: source.entrySet())
		{
			var key = entry.getKey();
			var value = entry.getValue();
			if (value instanceof Map)
			{
				var child = target.computeIfAbsent(key, k -> createNode(target));
				overlay((Map<K, Object>) child, (Map<K, Object>) value);
			}
			else
			{
				target.put(key, value);
			}
		}
	}

	/**
	 * Recursively collect the objects contained in a possibly multilevel mapping into a List
	 * in key order
	 */
	@SuppressWarnings("unchecked")
	public static <K, V> List<V> flatten(Map<K, Object> root)
	{
		var result = new ArrayList<V>();
		for (var value: root.values())
		{
			if (value instanceof Map)
			{
				result.addAll(flatten((Map<K, Object>) value));
			}
			else if (value instanceof Collection)
			{
				result.addAll((Collection<? extends V>) value);
			}
			else
			{
				result.add((V) value);
			}
		}
		return result;
	}

	/**
	 * Create a sorted and compressed version of a multilevel mapping in which all intermediate
	 * maps of size 1 are removed and the contained key glued to the parent key
	 */
	@SuppressWarnings("unchecked")
	public static <K> TreeMap<String, Object> compress(Map<K, Object> root, String glue)
	{
		var result = new TreeMap<String, Object>();
		for (var entry: root.entrySet())
		{
			// find the deepest Map.Entry that is reachable through a linear
			// chain and has a Map as value, collecting the keys on the path
			// to it
			var keyChain = new ArrayList<String>();
			var currentEntry = entry;
			while (true)
			{
				keyChain.add(currentEntry.getKey().toString());
				if (!(currentEntry.getValue() instanceof Map))
				{
					break;
				}
				var childMap = (Map<K, Object>) currentEntry.getValue();
				if (childMap.size() != 1)
				{
					break;
				}
				var childEntry = childMap.entrySet().iterator().next();
				if (!(childEntry.getValue() instanceof Map))
				{
					break;
				}
				currentEntry = childEntry;
			}
			result.put(String.join(glue, keyChain), (currentEntry.getValue() instanceof Map) ?
					compress((Map<K, Object>) currentEntry.getValue(), glue) :
					currentEntry.getValue()
			);
		}
		return result;
	}

	/**
	 * Transform a hierarchical data structure (either a List or a Map) into output by
	 * recursively iterating over it and applying, on each iteration level, the filtering
	 * and mapping directives specified in the given CerberusLogic RuleSet
	 *
	 * @param source hierarchical data structure to transform, optionally wrapped into an OrionQL.Proxy
	 * @param ruleSet transformation rules to apply to the input object
	 * @return result of applying the transformation specified in <tt>ruleSet</tt> to <tt>source</tt>
	 */
	@SuppressWarnings({"unchecked"})
	public static Object transform(Object source, CerberusLogic.RuleSet ruleSet)
	{
		// prepare pre- and post-rules by nesting level
		var preRules = new HashMap<Integer, CerberusLogic.RuleSet>();
		var postRules = new HashMap<Integer, CerberusLogic.RuleSet>();
		var deepestNesting = -1;
		for (var rule: ruleSet.getRules())
		{
			var nesting = Integer.parseInt(rule.getId());
			((nesting >= deepestNesting) ? preRules : postRules).computeIfAbsent(nesting, k -> new CerberusLogic.RuleSet()).addRule(rule);
			if (nesting > deepestNesting)
			{
				deepestNesting = nesting;
			}
		}
		// process the data
		var iteratorHierarchy = new LinkedList<Iterator<Object>>();
		var parentHierarchy = new LinkedList<OrionQL.Proxy<Object>>();
		var resultStack = new LinkedList<>();
		var currentObject = OrionQL.unwrap(source);
		var currentObjectProxied = OrionQL.wrap(source, null);
		// on every loop iteration start, we have the next object to process in currentObject,
		// and in the stacks the iteration hierarchy (empty for the root object)
		while (true)
		{
			var isDiscarded = false;
			var isTransformed = false;
			// apply pre-rules
			var rules = preRules.get(iteratorHierarchy.size());
			if (rules != null)
			{
				var decision = rules.approve(currentObjectProxied);
				if ((decision == null) || !decision.isAccept())
				{
					isDiscarded = true;
				}
				else if (decision.getActionId() != null)
				{
					isTransformed = true;
					currentObject = currentObjectProxied.access(decision.getActionId());
				}
			}
			// setup sub-iteration if applicable
			if (!isDiscarded && !isTransformed && (deepestNesting > iteratorHierarchy.size()))
			{
				var payload = (currentObject instanceof Map.Entry) ? ((Map.Entry<?, ?>) currentObject).getValue() : currentObject;
				if ((payload instanceof Map) || (payload instanceof Iterable))
				{
					var iterable = (payload instanceof Map) ? ((Map<Object, Object>) payload).entrySet() : (Iterable<Object>) payload;
					var iterator = ((Iterable<Object>) iterable).iterator();
					if (iterator.hasNext())
					{
						iteratorHierarchy.push(iterator);
						parentHierarchy.push(currentObjectProxied);
						if (payload instanceof Map)
						{
							resultStack.push((payload instanceof TreeMap) ? new TreeMap<>() : new HashMap<>());
						}
						else {
							resultStack.push(new ArrayList<>());
						}
						currentObject = iterator.next();
						currentObjectProxied = currentObjectProxied.derive(currentObject);
						continue;
					}
				}
			}
			// ingest the object into its parent result and prepare the next object
			while (true)
			{
				if (iteratorHierarchy.isEmpty())
				{
					// we are at the root object (isDiscarded is ignored instead of complaining)
					return currentObject;
				}
				// ingest the object into the parent result
				if (!isDiscarded)
				{
					var parentResult = resultStack.peek();
					if (parentResult instanceof Map)
					{
						((Map<Object, Object>) parentResult).put(
								currentObjectProxied.access("key"),
								(currentObject instanceof Map.Entry) ?
										((Map.Entry<?, ?>) currentObject).getValue() :
										currentObject);
					}
					else
					{
						// Dear code analyzer, the Method invocation 'add', like the three others
						// below, can *NOT* produce a 'NullPointerException'!
						// noinspection ConstantConditions
						((List<Object>) parentResult).add(currentObject);
					}
				}
				// prepare the next object to process
				// noinspection ConstantConditions
				if ((iteratorHierarchy.peek().hasNext()))
				{
					// noinspection ConstantConditions
					currentObject = iteratorHierarchy.peek().next();
					// noinspection ConstantConditions
					currentObjectProxied = parentHierarchy.peek().derive(currentObject);
					break;
				}
				else
				{
					// pop iteration and post-process iteration result
					iteratorHierarchy.pop();
					currentObject = resultStack.pop();
					currentObjectProxied = parentHierarchy.pop();
					// by default, empty results are discarded
					isDiscarded = (currentObject instanceof Map) ?
							((Map<Object, Object>) currentObject).isEmpty() :
							((List<Object>) currentObject).isEmpty();
					// apply post-rules (can override isDiscarded)
					rules = postRules.get(iteratorHierarchy.size());
					if (rules != null)
					{
						var input = currentObject;
						// when the initial object was a Map.Entry, make sure we also give one
						// to the post-rule for consistency with calling pre-rules
						if ((currentObjectProxied.access("") instanceof Map.Entry) && !(input instanceof Map.Entry))
						{
							input = new AbstractMap.SimpleEntry<>(currentObjectProxied.access("key"), input);
						}
						// for the root object's result we use the initial root object itself
						// as parent since we don't have access to its parent proxy
						var parent = parentHierarchy.isEmpty() ? currentObjectProxied : parentHierarchy.peek();
						var proxy = parent.derive(input);
						var decision = rules.approve(proxy);
						isDiscarded = (decision == null) || !decision.isAccept();
						if (!isDiscarded && (decision.getActionId() != null))
						{
							currentObject = proxy.access(decision.getActionId());
						}
					}
					// continue with ingesting the result into the parent iteration's result
				}
			}
		}
	}

	/**
	 * Pretty-print a possibly multilevel mapping into a String
	 *
	 * @param labelWidth  minimum field width for the map keys (-1 = none, 0 = default)
	 * @param renderFrame controls rendering the {} frame for the top level map
	 */
	@SuppressWarnings({"unchecked", "MagicNumber"})
	public static <K> String pprint(Map<K, Object> root, int labelWidth, boolean renderFrame)
	{
		var labelFormat = (labelWidth < 0) ?
				"%s%s = " :
				String.format("%%s%%-%ds = ", (labelWidth == 0) ? 20 : labelWidth);
		var result = new StringBuilder();
		if (renderFrame)
		{
			result.append("{\n");
		}
		var stack = new LinkedList<Iterator<Map.Entry<K, Object>>>();
		var current = root.entrySet().iterator();
		var indent = renderFrame ? "    " : "";
		while (current != null)
		{
			if (current.hasNext())
			{
				var entry = current.next();
				var key = (entry.getKey() == null) ? "null" : entry.getKey().toString();
				var lines = key.split("\n", -1);
				var lastLine = lines.length - 1;
				for (var i = 0; i < lastLine; ++i)
				{
					result.append(String.format("%s%s\n", indent, lines[i]));
				}
				String label = String.format(labelFormat, indent, lines[lastLine]);
				result.append(label);
				var value = entry.getValue();
				if (value == null)
				{
					value = "null";
				}
				if (value instanceof Map)
				{
					result.append("{\n");
					stack.push(current);
					current = ((Map<K, Object>) value).entrySet().iterator();
					indent = "    ".repeat(stack.size() + (renderFrame ? 1 : 0));
				}
				else if (value instanceof List)
				{
					result.append("[\n");
					for (var item: (List<Object>) value)
					{
						result.append(String.format("    %s%s\n", indent, item));
					}
					result.append(String.format("%s]\n", indent));
				}
				else if (value instanceof Date)
				{
					result.append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss'\n'").format(value));
				}
				else
				{
					lines = value.toString().split("\n", -1);
					result.append(String.format("%s\n", lines[0]));
					if (lines.length > 1)
					{
						var padding = " ".repeat(label.length());
						for (var i = 1; i < lines.length; ++i)
						{
							result.append(String.format("%s%s\n", padding, lines[i]));
						}
					}
				}
			}
			else
			{
				if (!stack.isEmpty())
				{
					current = stack.pop();
					indent = "    ".repeat(stack.size() + (renderFrame ? 1 : 0));
					result.append(String.format("%s}\n", indent));
				}
				else
				{
					current = null;
					if (renderFrame)
					{
						result.append("}\n");
					}
				}
			}
		}
		if (!result.isEmpty())
		{
			result.deleteCharAt(result.length() - 1);
		}
		return result.toString();
	}

	/**
	 * Write a minimum two levels deep mapping representing the rows of a matrix to the given
	 * OutputStream as HTML table
	 */
	@SuppressWarnings({"unchecked", "MethodWithTooManyParameters"})
	public static <K> void writeHtmlMatrix(Map<K, Object> root, OutputStream outputStream, String charset, CellMapper cellMapper, boolean doTotals, boolean rotateHeaders) throws IOException
	{
		if (root.isEmpty())
		{
			return;
		}
		var columns = createNode(root);
		for (var row: root.values())
		{
			columns.putAll((Map<K, Object>) row);
		}
		if (columns.isEmpty())
		{
			return;
		}
		if (doTotals)
		{
			for (var column : columns.entrySet())
			{
				column.setValue(0.0);
			}
		}
		var writer = new OutputStreamWriter(outputStream, charset);
		writer.write("<table>");
		if ((columns.size() > 1) || !"".equals(columns.keySet().iterator().next()))
		{
			writer.write("<thead><tr><th></th>");
			for (var columnKey : columns.keySet())
			{
				var header = (columnKey instanceof OrionQL.HtmlContainer) ? columnKey.toString() : StringEscapeUtils.escapeHtml(columnKey.toString());
				writer.write(String.format(rotateHeaders ? "<th><div class=\"vertical-text\"><div>%s</div></div></th>" : "<th>%s</th>", header));
			}
			if (doTotals && (columns.size() > 1))
			{
				writer.write(String.format(rotateHeaders ? "<th><div class=\"vertical-text\"><div>%s</div></div></th>" : "<th>%s</th>", "TOTAL"));
			}
			writer.write("</tr></thead>");
		}
		Boolean isIntegerMatrix = null;
		writer.write("<tbody>");
		for (var row: root.entrySet())
		{
			var total = 0.0;
			writer.write("<tr>");
			var cells = (Map<K, Object>) row.getValue();
			K rowKey = row.getKey();
			var header = (rowKey instanceof OrionQL.HtmlContainer) ? rowKey.toString() : StringEscapeUtils.escapeHtml(rowKey.toString());
			writer.write(String.format("<th>%s</th>", header));
			for (var column: columns.entrySet())
			{
				var value = cells.get(column.getKey());
				if (doTotals && (value != null))
				{
					var numberValue = (value instanceof OrionQL.HtmlContainer) ?
							((OrionQL.HtmlContainer) value).getNumberValue() :
							(Number) value;
					if (isIntegerMatrix == null)
					{
						isIntegerMatrix = (numberValue instanceof Integer);
					}
					total += numberValue.doubleValue();
					column.setValue(((Number) column.getValue()).doubleValue() + numberValue.doubleValue());
				}
				var cell = (cellMapper == null) ? value : cellMapper.map(value);
				var text = (cell == null) ?
						"" :
						(cell instanceof OrionQL.HtmlContainer) ? cell.toString() : StringEscapeUtils.escapeHtml(cell.toString());
				writer.write(String.format(doTotals ? "<td class=\"numeric\">%s</td>" : "<td>%s</td>", text));
			}
			if (doTotals && (columns.size() > 1))
			{
				writer.write(String.format("<td class=\"numeric\">%s</td>", formatTotal(total, isIntegerMatrix)));
			}
			writer.write("</tr>");
		}
		if (doTotals)
		{
			writer.write("<tr><th>TOTAL</th>");
			var total = 0.0;
			for (var columnTotal: columns.values())
			{
				writer.write(String.format("<td class=\"numeric\">%s</td>", formatTotal((Double) columnTotal, isIntegerMatrix)));
				total += (Double) columnTotal;
			}
			if (columns.size() > 1)
			{
				writer.write(String.format("<td class=\"numeric\">%s</td>", formatTotal(total, isIntegerMatrix)));
			}
			writer.write("</tr>");
		}
		writer.write("</tbody></table>");
		writer.flush();
	}

	private static @NotNull Object formatTotal(Double total, Boolean asInteger)
	{
		return ((asInteger != null) && asInteger) ? total.intValue() : String.format("%.02f", total);
	}

	/**
	 * Write a minimum numCaptionLevels + 2 levels deep mapping to the given OutputStream as
	 * matrices separated by captions. If numCaptionLevels == 0, this method is equivalent to
	 * {@link #writeHtmlMatrix(Map, OutputStream, String, CellMapper, boolean, boolean)}.
	 */
	@SuppressWarnings({"unchecked", "MethodWithTooManyParameters"})
	public static <K> void writeHtmlMatrices(Map<K, Object> root, int numCaptionLevels, OutputStream outputStream, String charset, CellMapper cellMapper, boolean doTotals, boolean rotateHeaders, boolean addNavigation) throws IOException
	{
		if (numCaptionLevels < 1)
		{
			writeHtmlMatrix(root, outputStream, charset, cellMapper, doTotals, rotateHeaders);
		}
		else
		{
			// @formatter:off
			writeHtmlSections(root, numCaptionLevels, outputStream, charset, addNavigation,
				o -> writeHtmlMatrix((Map<K, Object>) o, outputStream, charset, cellMapper, doTotals, rotateHeaders)
			);
			// @formatter:on
		}
	}

	/**
	 * Write a numCaptionLevels levels deep mapping containing in the leaf positions
	 * Iterables of Proxies to the given OutputStream as tables separated by captions.
	 */
	@SuppressWarnings({"unchecked", "MethodWithTooManyParameters"})
	public static <K> void writeHtmlTables(Map<K, Object> root, int numCaptionLevels, OutputStream outputStream, String charset, TabularData writer, boolean addNavigation) throws IOException
	{
		writer.setOutputStream(outputStream, charset);
		// @formatter:off
		writeHtmlSections(root, numCaptionLevels, outputStream, charset, addNavigation,
			o -> writer.writeHtmlTable(((Iterable<OrionQL.Proxy<Object>>) o).iterator())
		);
		// @formatter:on
	}

	/**
	 * We would use java.util.function.{@link Consumer} if it could pass
	 * checked exceptions
	 */
	private interface DataWriter
	{
		void accept(Object data) throws IOException;
	}

	/**
	 * Worker method for writing data from a mapping in as
	 * matrices separated by captions.
	 */
	@SuppressWarnings({"unchecked", "MethodWithTooManyParameters"})
	private static <K> void writeHtmlSections(Map<K, Object> root, int numCaptionLevels, OutputStream outputStream, String charset, boolean addNavigation, DataWriter dataWriter) throws IOException
	{
		if (root.isEmpty())
		{
			return;
		}
		var navigator = new StringBuilder();
		navigator.append("<div id=\"navtoggle\">&target;</div>");
		navigator.append("<div id=\"navigator\"><h3>Navigation</h3>");
		var stack = new LinkedList<Iterator<Map.Entry<K, Object>>>();
		var path = new ArrayList<String>();
		var current = root.entrySet().iterator();
		while (true)
		{
			if (current.hasNext())
			{
				var entry = current.next();
				var key = (entry.getKey() == null) ? "null" : entry.getKey().toString();
				path.add(key);
				var id = String.join("/", path);
				var anchor = addNavigation ? String.format("<a name=\"/%s\"></a>", id) : "";
				var breadcrumb = String.join(" &rtrif; ", path);
				var headingLevel = path.size() + 1;
				var expand = (path.size() <= 2) ? " checked" : "";
				outputStream.write(String.format("\n<h%d title=\"%s\">%s%s</h%d>", headingLevel, breadcrumb, key, anchor, headingLevel).getBytes(charset));
				if (path.size() < numCaptionLevels)
				{
					navigator.append(String.format("<input id=\"%s\" type=\"checkbox\"%s/><div><label for=\"%s\" class=\"toggle\"></label><a title=\"%s\" href=\"#/%s\">%s</a></div><div class=\"folder\">", id, expand, id, breadcrumb, id, key));
					stack.push(current);
					current = ((Map<K, Object>) entry.getValue()).entrySet().iterator();
				}
				else
				{
					path.remove(path.size() - 1);
					navigator.append(String.format("<div><label></label><a title=\"%s\" href=\"#/%s\">%s</a></div>", breadcrumb, id, key));
					outputStream.write("\n".getBytes(charset));
					dataWriter.accept(entry.getValue());
				}
			}
			else
			{
				if (stack.isEmpty())
				{
					navigator.append("</div>");
					outputStream.write("\n".getBytes(charset));
					if (addNavigation)
					{
						outputStream.write(navigator.toString().getBytes(charset));
					}
					return;
				}
				current = stack.pop();
				path.remove(path.size() - 1);
				navigator.append("</div>");
			}
		}
	}
}
