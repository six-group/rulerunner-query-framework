package com.six.iam.util;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.*;

public class Literals
{
	private static final String TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss";

	public static class MapBuilder<K, V> extends HashMap<K, V>
	{
		private MapBuilder(K key, V value)
		{
			add(key, value);
		}

		public MapBuilder<K, V> add(K key, V value)
		{
			put(key, value);
			return this;
		}
	}

	public static class SortedMapBuilder<K, V> extends TreeMap<K, V>
	{
		private SortedMapBuilder(K key, V value)
		{
			add(key, value);
		}

		public SortedMapBuilder<K, V> add(K key, V value)
		{
			put(key, value);
			return this;
		}
	}

	/**
	 * <p>Create a Map literal using a construct of the form</p>
	 * <p><tt>asMap(key1, value1).add(key2, value2)...</tt></p>
	 * <p>Any number of key value pairs can be added using chained
	 * <tt>add()</tt> calls.</p>
	 * <p>The parameters of the leading <tt>asMap()</tt> call determine
	 * the Map's key and value type. Cast to a parent type if necessary.</p>
	 * <p>See {@link #asSortedMap(K, V)} to create sorted maps.</p>
	 * @param key key of first entry
	 * @param value value of first entry
	 * @param <K> key type
	 * @param <V> value type
	 * @return builder object that can be used as Map as well as for adding more entries
	 */
	public static <K, V> MapBuilder<K, V> asMap(K key, V value)
	{
		return new MapBuilder<>(key, value);
	}

	/**
	 * Variant of {@link #asMap(K, V)} for creating sorted maps.
	 */
	public static <K, V> SortedMapBuilder<K, V> asSortedMap(K key, V value)
	{
		return new SortedMapBuilder<>(key, value);
	}

	/**
	 * Create a java.util.Date object from a String in yyyy-mm-dd HH:MM:SS format.
	 */
	public static Date asDate(String text)
	{
		return new SimpleDateFormat(TIMESTAMP_FORMAT).parse(text, new ParsePosition(0));
	}
}
