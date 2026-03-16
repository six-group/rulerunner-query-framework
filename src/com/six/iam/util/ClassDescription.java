package com.six.iam.util;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import sailpoint.object.Resolver;

/**
 * Helper class to map object attribute names to getter methods. Get the ClassDescription
 * for some class by invoking <tt>ClassDescription.get(clazz)</tt>, then query it for the
 * parameterless getters using <tt>getSimpleGetter()</tt>, for the getters taking a
 * <tt>Resolver</tt> parameter using <tt>getResolvingGetter()</tt>, and for the
 * <tt>Attributes</tt> getter using <tt>getAttributesGetter()</tt>.
 */
public class ClassDescription
{
	private static final Map<Class<?>, ClassDescription> descriptions = new ConcurrentHashMap<>();

	private final Map<String, Method> simpleGetters;
	private final Map<String, Method> resolvingGetters;
	private final Map<String, Map<Class<?>, Method>> parameterizedGetters;
	private final Method attributesGetter;

	private ClassDescription(Map<String, Method> simpleGetters, Map<String, Method> resolvingGetters, Map<String, Map<Class<?>, Method>> parameterizedGetters, Method attributesGetter)
	{
		this.simpleGetters = simpleGetters;
		this.resolvingGetters = resolvingGetters;
		this.parameterizedGetters = parameterizedGetters;
		this.attributesGetter = attributesGetter;
	}

	Method getSimpleGetter(String name)
	{
		return simpleGetters.get(name);
	}

	Method getResolvingGetter(String name)
	{
		return resolvingGetters.get(name);
	}

	Method getParameterizedGetter(String name, Class<?> parameterClass)
	{
		var getters = parameterizedGetters.get(name);
		if (getters != null)
		{
			// ensure we find methods also if the declared parameter type is an interface or superclass
			while (parameterClass != null)
			{
				var getter = getters.get(parameterClass);
				if (getter != null)
				{
					return getter;
				}
				for (var iface : parameterClass.getInterfaces())
				{
					getter = getters.get(iface);
					if (getter != null)
					{
						return getter;
					}
				}
				parameterClass = parameterClass.getSuperclass();
			}
		}
		return null;
	}

	Method getAttributesGetter()
	{
		return attributesGetter;
	}

	public static ClassDescription get(Class<?> objectClass)
	{
		var result = descriptions.get(objectClass);
		if (result == null)
		{
			var simpleGetters = new HashMap<String, Method>();
			var resolvingGetters = new HashMap<String, Method>();
			var parameterizedGetters = new HashMap<String, Map<Class<?>, Method>>();
			Method attributesGetter = null;
			var attributesSelector = Pattern.compile("(get|is)([A-Z]+)(.*)");
			// for private classes, fall back to the interfaces
			var classes = (Modifier.isPublic(objectClass.getModifiers())) ? new Class[]{objectClass} : objectClass.getInterfaces();
			for (var testee: classes)
			{
				for (var method : testee.getMethods())
				{
					var matcher = attributesSelector.matcher(method.getName());
					if (matcher.matches())
					{
						var head = matcher.group(2);
						var tail = matcher.group(3);
						//@formatter:off
						var name = (head.length() > 1) ?
								head.substring(0, head.length() - 1).toLowerCase() + head.substring(head.length() - 1) + tail :
								head.toLowerCase() + tail;
						//@formatter:on
						if (method.getParameterCount() == 0)
						{
							simpleGetters.put(name, method);
						}
						else if (method.getParameterCount() == 1)
						{
							if (method.getParameterTypes()[0] == Resolver.class)
							{
								resolvingGetters.put(name, method);
							}
							else
							{
								var getters = parameterizedGetters.computeIfAbsent(name, k -> new HashMap<>());
								getters.put(method.getParameterTypes()[0], method);
							}
						}
					}
					if ("getAttributes".equals(method.getName()) && method.getParameterCount() == 0)
					{
						attributesGetter = method;
					}
				}
			}
			result = new ClassDescription(simpleGetters, resolvingGetters, parameterizedGetters, attributesGetter);
			descriptions.put(objectClass, result);
		}
		return result;
	}
}
