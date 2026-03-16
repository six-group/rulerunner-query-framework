package com.six.iam.util;

import org.junit.Test;

import sailpoint.api.SailPointContext;
import sailpoint.object.Attributes;
import sailpoint.object.Configuration;
import sailpoint.tools.GeneralException;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalToObject;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.when;

public class MacroLibraryTest
{
	@Test
	public void test_macro_loading() throws GeneralException
	{
		// Arrange
		var globalMacros = Literals.asMap("$includes$", "nested").add("global", "global#expanded").add("nested.overridden", "nested#overridden");
		var nestedMacros = Literals.asMap("$includes$", "inner").add("nested", "nested#expanded").add("overridden", "nested#original");
		var innerMacros = Literals.asMap("inner", "inner#expanded");
		var globalExpected = Literals.asMap("global", "global#expanded").add("nested.nested", "nested#expanded").add("nested.overridden", "nested#overridden").add("nested.inner.inner", "inner#expanded");
		var nestedExpected = Literals.asMap("nested", "nested#expanded").add("overridden", "nested#original").add("inner.inner", "inner#expanded");
		var globalLibrary = new Configuration();
		globalLibrary.setAttributes(new Attributes<>(globalMacros));
		var nestedLibrary = new Configuration();
		nestedLibrary.setAttributes(new Attributes<>(nestedMacros));
		var innerLibrary = new Configuration();
		innerLibrary.setAttributes(new Attributes<>(innerMacros));
		var context = mock(SailPointContext.class);
		when(context.getObject(Configuration.class, "OrionLibrary")).thenReturn(globalLibrary);
		when(context.getObject(Configuration.class, "OrionLibrary.nested")).thenReturn(nestedLibrary);
		when(context.getObject(Configuration.class, "OrionLibrary.inner")).thenReturn(innerLibrary);
		// Act
		var globalMacroLibrary = new MacroLibrary("OrionLibrary", context).load();
		var nestedMacroLibrary = new MacroLibrary("OrionLibrary", context).load("nested");
		assertThat("Incorrect macro loading", globalMacroLibrary, equalToObject(globalExpected));
		assertThat("Incorrect macro loading", nestedMacroLibrary, equalToObject(nestedExpected));
	}
}