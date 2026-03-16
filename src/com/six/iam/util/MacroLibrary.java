package com.six.iam.util;

import java.util.HashMap;
import sailpoint.api.SailPointContext;
import sailpoint.object.Configuration;
import sailpoint.tools.GeneralException;

/**
 * Objects of this class can be used to supply a macro library loaded from one or more
 * {@link Configuration} objects to {@link OrionQL} or {@link CerberusLogic}
 */
public class MacroLibrary extends HashMap<String, String>
{
	private final String libraryName;
	private final SailPointContext resolver;

	/**
	 *
	 * @param libraryName Name of the <tt>Configuration</tt> object that contains the root library
	 * @param resolver SailPointContext to use for loading the Configuration object
	 */
	public MacroLibrary(String libraryName, SailPointContext resolver)
	{
		this.libraryName = libraryName;
		this.resolver = resolver;
	}

	/**
	 * Load the root library if the <tt>Configuration</tt> object <tt><i>libraryName</i></tt> exists.
	 * The entries of included libraries are scoped (recursively).
	 *
	 * @return <tt>this</tt>
	 */
	@SuppressWarnings("unused")
	public MacroLibrary load() throws GeneralException
	{
		load(libraryName, null);
		return this;
	}

	/**
	 * Load a sub-library if the <tt>Configuration</tt> object <tt><i>libraryName</i>.<i>subName</i></tt> exists.
	 * The entries are scoped with <tt>subName</tt>, and entries of included libraries are scoped recursively.
	 *
	 * @param subName name of the sub-library
	 * @return <tt>this</tt>
	 */
	@SuppressWarnings("unused")
	public MacroLibrary load(String subName) throws GeneralException
	{
		load(String.format("%s.%s", libraryName, subName), null);
		return this;
	}

	private void load(String name, String scopeName) throws GeneralException
	{
		var library = resolver.getObject(Configuration.class, name);
		if (library != null)
		{
			var attributes = library.getAttributes();
			if (attributes != null)
			{
				var includes = attributes.getStringList("$includes$");
				var macros = new HashMap<>(attributes);
				macros.remove("$includes$");
				if (includes != null)
				{
					for (var include : includes)
					{
						var nestedScopeName = (scopeName == null) ? include : String.format("%s.%s", scopeName, include);
						load(String.format("%s.%s", libraryName, include), nestedScopeName);
					}
				}
				for (var macro : macros.entrySet())
				{
					if ((macro.getValue() != null) && !macro.getKey().endsWith("$description"))
					{
						var macroName = (scopeName == null) ? macro.getKey() : String.format("%s.%s", scopeName, macro.getKey());
						put(macroName, macro.getValue().toString());
					}
				}
			}
		}
	}
}
