package com.six.iam.util;

import java.util.*;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import sailpoint.api.PersistenceManager;
import sailpoint.object.*;
import sailpoint.tools.GeneralException;

public class QueryHelper
{
	private static final Pattern COMMA_SEPARATOR = Pattern.compile(", *");

	private static class ObjectIterator<T extends SailPointObject> implements Iterator<T>
	{
		private final Class<T> objectClass;
		private final Iterator<Object[]> ids;
		private final PersistenceManager resolver;
		private T current = null;

		/**
		 * Iterate over the objects returned by the specified search, silently skipping
		 * missing objects instead of throwing an ObjectNotFoundException.
		 */
		private ObjectIterator(Class<T> objectClass, QueryOptions queryOptions, PersistenceManager resolver) throws GeneralException
		{
			this.objectClass = objectClass;
			this.ids = resolver.search(objectClass, queryOptions, "id");
			this.resolver = resolver;
		}

		@SuppressWarnings("VariableNotUsedInsideIf")
		@Override
		public boolean hasNext()
		{
			if (current != null)
			{
				return true;
			}
			while (ids.hasNext())
			{
				try
				{
					current = resolver.getObjectById(objectClass, (String) ids.next()[0]);
					if (current != null)
					{
						return true;
					}
				}
				catch (GeneralException e)
				{
					throw new RuntimeException(e);
				}
			}
			return false;
		}

		@Override
		public T next()
		{
			if (!hasNext())
			{
				throw new NoSuchElementException();
			}
			var result = current;
			current = null;
			return result;
		}
	}

	public static class TransformingIterator<InType, OutType> implements Iterator<OutType>
	{
		private final Iterator<InType> input;
		private final Function<InType, OutType> mapper;

		/**
		 * Wrap an iterator to apply a mapper function to each element
		 *
		 * @param input iterator to wrap
		 * @param mapper function to map input object to output object
		 */
		public TransformingIterator(Iterator<InType> input, Function<InType, OutType> mapper)
		{
			this.input = input;
			this.mapper = mapper;
		}

		@Override
		public boolean hasNext()
		{
			return input.hasNext();
		}

		@Override
		public OutType next()
		{
			return mapper.apply(input.next());
		}
	}

	/**
	 * <p>Wrapper around {@link PersistenceManager#search(Class, QueryOptions, String)}
	 * fetching the results as key value maps instead of object arrays and allowing
	 * to access extended attributes as well. Instead of explicitly naming the desired
	 * extended attributes, an empty attribute name can be specified to select <i>all</i>
	 * extended attributes. In this case, extended attributes take precedence over
	 * regular attributes with the same name.</p>
	 */
	public static <T extends SailPointObject> Iterator<Map<String, Object>> search(PersistenceManager context, Class<T> objectClass, QueryOptions queryOptions, String attributes) throws GeneralException
	{
		return search(context, objectClass, queryOptions, Arrays.asList(COMMA_SEPARATOR.split(attributes, -1)));
	}

	/**
	 * <p>Wrapper around {@link PersistenceManager#search(Class, QueryOptions, List)}
	 * fetching the results as key value maps instead of object arrays and allowing
	 * to access extended attributes as well. Instead of explicitly naming the desired
	 * extended attributes, an empty attribute name can be specified to select <i>all</i>
	 * extended attributes. In this case, extended attributes take precedence over
	 * regular attributes with the same name.</p>
	 */
	public static <T extends SailPointObject> Iterator<Map<String, Object>> search(PersistenceManager context, Class<T> objectClass, QueryOptions queryOptions, List<String> attributes) throws GeneralException
	{
		List<String> regularAttributes = new ArrayList<>();
		List<String> extendedAttributes = new ArrayList<>();
		var classDescription = ClassDescription.get(objectClass);
		var objectConfig = ObjectConfig.getObjectConfig(objectClass);
		for (var attribute: attributes)
		{
			if ((attribute.contains(".")) || (classDescription.getSimpleGetter(attribute) != null) || (classDescription.getResolvingGetter(attribute) != null))
			{
				regularAttributes.add(attribute);
			}
			else
			{
				var objectAttributeConfig = (objectConfig == null) ? null : objectConfig.getObjectAttribute(attribute);
				if ((objectAttributeConfig != null) && objectAttributeConfig.isSearchable())
				{
					regularAttributes.add(attribute);
				}
				else
				{
					extendedAttributes.add(attribute);
				}
			}
		}
		if (extendedAttributes.isEmpty())
		{
			extendedAttributes = null;
		}
		else if (extendedAttributes.contains(""))
		{
			extendedAttributes.clear();
		}
		return search(context, objectClass, queryOptions, regularAttributes, extendedAttributes);
	}

	/**
	 * <p>Wrapper around {@link PersistenceManager#search(Class, QueryOptions, List)}
	 * fetching the results as key value maps instead of object arrays and allowing
	 * to access extended attributes as well. Instead of explicitly naming the desired
	 * extended attributes, an empty list can be specified to select <i>all</i>
	 * extended attributes.</p>
	 * <p>Note that extended attributes take precedence over regular attributes with
	 * the same name.</p>
	 * @param context {@link PersistenceManager} used by the search
	 * @param objectClass see {@link PersistenceManager#search(Class, QueryOptions, List)}
	 * @param queryOptions see {@link PersistenceManager#search(Class, QueryOptions, List)}
	 * @param regularAttributes list of attributes to fetch from database columns directly
	 * @param extendedAttributes list of attributes to fetch from <tt>attributes</tt> mapping
	 */
	@SuppressWarnings("unchecked")
	public static <T extends SailPointObject> Iterator<Map<String, Object>> search(PersistenceManager context, Class<T> objectClass, QueryOptions queryOptions, List<String> regularAttributes, List<String> extendedAttributes) throws GeneralException
	{
		var queryAttributes = new ArrayList<>(regularAttributes);
		if ((extendedAttributes != null) && !queryAttributes.contains("attributes"))
		{
			queryAttributes.add("attributes");
		}
		var attributesIndex = queryAttributes.indexOf("attributes");
		Function<Object[], Map<String, Object>> mapper = row -> {
			var result = new HashMap<String, Object>();
			var columns = regularAttributes.iterator();
			for (var value: row)
			{
				if (columns.hasNext())
				{
					var attributeName = columns.next();
					if (value != null)
					{
						result.put(attributeName, value);
					}
				}
			}
			if (extendedAttributes != null)
			{
				var attributes = (Map<String, Object>) row[attributesIndex];
				if (attributes != null)
				{
					if (extendedAttributes.isEmpty())
					{
						result.putAll(attributes);
					}
					else
					{
						for (var attribute : extendedAttributes)
						{
							var value = attributes.get(attribute);
							if (value != null)
							{
								result.put(attribute, value);
							}
						}
					}
				}
			}
			return result;
		};
		return new TransformingIterator<>(context.search(objectClass, queryOptions, queryAttributes), mapper);
	}

	/**
	 * <p>Substitute for {@link PersistenceManager#search(Class, QueryOptions)} that does
	 * not throw an ObjectNotFoundException "No row with the given identifier exists" when
	 * objects are deleted from underneath the ongoing iteration. The missing objects will
	 * be silently skipped instead (effectively pretending they were not present from
	 * the beginning).</p>
	 * <p>If your iteration needs to survive commits, use {@link QueryOptions#setCloneResults(boolean)}.</p>
	 * @param context {@link PersistenceManager} used by the search
	 * @param objectClass see {@link PersistenceManager#search(Class, QueryOptions)}
	 * @param queryOptions see {@link PersistenceManager#search(Class, QueryOptions)}
	 */
	public static <T extends SailPointObject> Iterator<T> search(PersistenceManager context, Class<T> objectClass, QueryOptions queryOptions) throws GeneralException
	{
		return new ObjectIterator<>(objectClass, queryOptions, context);
	}
}
