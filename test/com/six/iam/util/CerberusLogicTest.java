package com.six.iam.util;

import org.junit.Test;
import java.util.*;
import java.util.Map;

import sailpoint.object.ProvisioningPlan;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

public class CerberusLogicTest
{
	private static class ProxyMock implements CerberusLogic.IAccessor
	{
		private final Object target;
		ProxyMock(Object target)
		{
			this.target = target;
		}

		@SuppressWarnings("unchecked")
		@Override
		public <V> V access(String expression)
		{
			if ("altAttr".equals(expression))
			{
				return (V) "Hello";
			}
			if ("numAttr".equals(expression))
			{
				return (V) Integer.valueOf(4);
			}
			return (V) target;
		}

		@Override
		public CerberusLogic.IAccessor derive(Object payload)
		{
			return new ProxyMock(payload);
		}
	}

	private final ProxyMock stringScalar = new ProxyMock("Hello world!");
	private final ProxyMock stringScalar_alt = new ProxyMock("Hello guys!");
	private final ProxyMock stringScalar_alt_1 = new ProxyMock("Hello");
	private final ProxyMock intScalar = new ProxyMock(27);
	private final ProxyMock intScalar_alt = new ProxyMock(28);
	private final ProxyMock booleanScalar = new ProxyMock(false);
	private final ProxyMock booleanScalar_alt = new ProxyMock(true);
	private final ProxyMock enumScalar = new ProxyMock(ProvisioningPlan.Operation.Add);
	private final ProxyMock stringCollection = new ProxyMock(Arrays.asList("Hello", "world!"));
	private final ProxyMock stringCollection_alt = new ProxyMock(Arrays.asList("Hello", "guys!"));
	private final ProxyMock stringCollection_alt_1 = new ProxyMock(Arrays.asList("Welcome", "guys!"));
	private final ProxyMock stringCollection_empty = new ProxyMock(Collections.emptyList());
	private final ProxyMock nullValue = new ProxyMock(null);

	@Test
	public void test_eq()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector(String.format("eq( \"attr\", \"%s\" )", stringScalar.target));
		// Act & Assert
		assertThat("String match not detected", selector.matches(stringScalar), is(true));
		assertThat("String mismatch not detected", selector.matches(stringScalar_alt), is(false));
		assertThat("Null mismatch not detected", selector.matches(nullValue), is(false));
	}

	@Test
	public void test_enum()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("eq( \"attr\", \"Add\" )");
		// Act & Assert
		assertThat("Enum match not detected", selector.matches(enumScalar), is(true));
	}

	@Test
	public void test_eq_multiarg()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector(String.format("eq( \"attr\", %s, %s, \"%s\", null )", intScalar.target, booleanScalar.target, stringScalar.target));
		// Act & Assert
		assertThat("String match not detected", selector.matches(stringScalar), is(true));
		assertThat("String mismatch not detected", selector.matches(stringScalar_alt), is(false));
		assertThat("Int match not detected", selector.matches(intScalar), is(true));
		assertThat("Int mismatch not detected", selector.matches(intScalar_alt), is(false));
		assertThat("Boolean match not detected", selector.matches(booleanScalar), is(true));
		assertThat("Boolean mismatch not detected", selector.matches(booleanScalar_alt), is(false));
		assertThat("Null match not detected", selector.matches(nullValue), is(true));
	}

	@Test
	public void test_ne()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector(String.format("ne( \"attr\", \"%s\" )", stringScalar_alt.target));
		// Act & Assert
		assertThat("String mismatch not detected", selector.matches(stringScalar), is(true));
		assertThat("String match not detected", selector.matches(stringScalar_alt), is(false));
		assertThat("Null mismatch not detected", selector.matches(nullValue), is(true));
	}

	@Test
	public void test_ne_multiarg()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector(String.format("ne( \"attr\", %s, %s, \"%s\", null )", intScalar_alt.target, booleanScalar_alt.target, stringScalar_alt.target));
		// Act & Assert
		assertThat("String mismatch not detected", selector.matches(stringScalar), is(true));
		assertThat("String match not detected", selector.matches(stringScalar_alt), is(false));
		assertThat("Int mismatch not detected", selector.matches(intScalar), is(true));
		assertThat("Int match not detected", selector.matches(intScalar_alt), is(false));
		assertThat("Boolean mismatch not detected", selector.matches(booleanScalar), is(true));
		assertThat("Boolean match not detected", selector.matches(booleanScalar_alt), is(false));
		assertThat("Null match not detected", selector.matches(nullValue), is(false));
	}

	@SuppressWarnings("MagicNumber")
	@Test
	public void test_between()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("between( \"attr\", 20, 30 )");
		// Act & Assert
		assertThat("Range mismatch not detected", selector.matches(new ProxyMock(19)), is(false));
		assertThat("Range match not detected", selector.matches(new ProxyMock(20)), is(true));
		assertThat("Range match not detected", selector.matches(new ProxyMock(21)), is(true));
		assertThat("Range match not detected", selector.matches(new ProxyMock(29)), is(true));
		assertThat("Range match not detected", selector.matches(new ProxyMock(30)), is(true));
		assertThat("Range mismatch not detected", selector.matches(new ProxyMock(31)), is(false));
		assertThat("Null mismatch not detected", selector.matches(nullValue), is(false));
	}

	@SuppressWarnings("MagicNumber")
	@Test
	public void test_outside()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("outside( \"attr\", 20, 30 )");
		// Act & Assert
		assertThat("Outside match not detected", selector.matches(new ProxyMock(19)), is(true));
		assertThat("Outside mismatch not detected", selector.matches(new ProxyMock(20)), is(false));
		assertThat("Outside mismatch not detected", selector.matches(new ProxyMock(21)), is(false));
		assertThat("Outside mismatch not detected", selector.matches(new ProxyMock(29)), is(false));
		assertThat("Outside mismatch not detected", selector.matches(new ProxyMock(30)), is(false));
		assertThat("Outside match not detected", selector.matches(new ProxyMock(31)), is(true));
		assertThat("Null mismatch not detected", selector.matches(nullValue), is(false));
	}

	@SuppressWarnings("MagicNumber")
	@Test
	public void test_ge()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("ge( \"attr\", 20 )");
		// Act & Assert
		assertThat("ge mismatch not detected", selector.matches(new ProxyMock(19)), is(false));
		assertThat("ge match not detected", selector.matches(new ProxyMock(20)), is(true));
		assertThat("ge match not detected", selector.matches(new ProxyMock(21)), is(true));
		assertThat("Null mismatch not detected", selector.matches(nullValue), is(false));
	}

	@SuppressWarnings("MagicNumber")
	@Test
	public void test_gt()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("gt( \"attr\", 20 )");
		// Act & Assert
		assertThat("gt mismatch not detected", selector.matches(new ProxyMock(19)), is(false));
		assertThat("gt mismatch not detected", selector.matches(new ProxyMock(20)), is(false));
		assertThat("gt match not detected", selector.matches(new ProxyMock(21)), is(true));
		assertThat("Null mismatch not detected", selector.matches(nullValue), is(false));
	}

	@SuppressWarnings("MagicNumber")
	@Test
	public void test_le()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("le( \"attr\", 20 )");
		// Act & Assert
		assertThat("le match not detected", selector.matches(new ProxyMock(19)), is(true));
		assertThat("le match not detected", selector.matches(new ProxyMock(20)), is(true));
		assertThat("le mismatch not detected", selector.matches(new ProxyMock(21)), is(false));
		assertThat("Null mismatch not detected", selector.matches(nullValue), is(false));
	}

	@SuppressWarnings("MagicNumber")
	@Test
	public void test_lt()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("lt( \"attr\", 20 )");
		// Act & Assert
		assertThat("lt match not detected", selector.matches(new ProxyMock(19)), is(true));
		assertThat("lt mismatch not detected", selector.matches(new ProxyMock(20)), is(false));
		assertThat("lt mismatch not detected", selector.matches(new ProxyMock(21)), is(false));
		assertThat("Null mismatch not detected", selector.matches(nullValue), is(false));
	}

	@Test
	public void test_in()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("in( \"altAttr\", \"attr\" )");
		// Act & Assert
		assertThat("In match not detected", selector.matches(stringCollection), is(true));
		assertThat("In mismatch not detected", selector.matches(stringCollection_alt_1), is(false));
		assertThat("In mismatch not detected", selector.matches(stringCollection_empty), is(false));
		assertThat("In mismatch not detected", selector.matches(stringScalar), is(false));
		assertThat("In match not detected", selector.matches(stringScalar_alt_1), is(true));
		assertThat("In mismatch not detected", selector.matches(nullValue), is(false));
	}

	@Test
	public void test_notin()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("notin( \"altAttr\", \"attr\" )");
		// Act & Assert
		assertThat("In match not detected", selector.matches(stringCollection), is(false));
		assertThat("In mismatch not detected", selector.matches(stringCollection_alt_1), is(true));
		assertThat("In mismatch not detected", selector.matches(stringCollection_empty), is(true));
		assertThat("In mismatch not detected", selector.matches(nullValue), is(true));
	}

	@Test
	public void test_like()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("like( \"attr\", \"world\" )");
		// Act & Assert
		assertThat("String match not detected", selector.matches(stringScalar), is(true));
		assertThat("String mismatch not detected", selector.matches(stringScalar_alt), is(false));
		assertThat("Null mismatch not detected", selector.matches(nullValue), is(false));
	}

	@Test
	public void test_like_multiarg()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("like( \"attr\", null, \"x\", \"world\" )");
		// Act & Assert
		assertThat("String match not detected", selector.matches(stringScalar), is(true));
		assertThat("String mismatch not detected", selector.matches(stringScalar_alt), is(false));
		assertThat("Null match not detected", selector.matches(nullValue), is(true));
	}

	@Test
	public void test_unlike()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("unlike( \"attr\", \"world\" )");
		// Act & Assert
		assertThat("String mismatch not detected", selector.matches(stringScalar), is(false));
		assertThat("String match not detected", selector.matches(stringScalar_alt), is(true));
		assertThat("Null mismatch not detected", selector.matches(nullValue), is(true));
	}

	@Test
	public void test_unlike_multiarg()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("unlike( \"attr\", null, \"x\", \"world\" )");
		// Act & Assert
		assertThat("String mismatch not detected", selector.matches(stringScalar), is(false));
		assertThat("String match not detected", selector.matches(stringScalar_alt), is(true));
		assertThat("Null match not detected", selector.matches(nullValue), is(false));
	}

	@Test
	public void test_any()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("any( \"attr\", eq( \"attr\", \"world!\" ) )");
		// Act & Assert
		assertThat("Any match not detected", selector.matches(stringCollection), is(true));
		assertThat("Any mismatch not detected", selector.matches(stringCollection_alt), is(false));
		assertThat("Any mismatch not detected", selector.matches(stringCollection_empty), is(false));
		assertThat("Any mismatch not detected", selector.matches(nullValue), is(false));
	}

	@Test
	public void test_any_map()
	{
		// Arrange
		var map = Literals.asMap("type", "String");
		// ProxyMock allows us to check only the iteration over the Map entry set
		var selector = CerberusLogic.compileSelector(String.format("any( \"attr\", eq( \"attr\", \"%s\" ) )", map.entrySet().iterator().next()));
		// Act & Assert
		assertThat("Map match not detected", selector.matches(new ProxyMock(map)), is(true));
	}

	@Test
	public void test_any_implicit_collection()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("any( \"attr\", eq( \"attr\", \"Hello world!\" ) )");
		// Act & Assert
		assertThat("Any match not detected", selector.matches(stringScalar), is(true));
		assertThat("Any mismatch not detected", selector.matches(stringScalar_alt), is(false));
		assertThat("Any mismatch not detected", selector.matches(nullValue), is(false));
	}

	@Test
	public void test_any_nocondition()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("any( \"attr\" )");
		// Act & Assert
		assertThat("Any match not detected", selector.matches(stringCollection), is(true));
		assertThat("Any mismatch not detected", selector.matches(stringCollection_empty), is(false));
		assertThat("Any mismatch not detected", selector.matches(nullValue), is(false));
	}

	@Test
	public void test_none()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("none( \"attr\", eq( \"attr\", \"world!\" ) )");
		// Act & Assert
		assertThat("None mismatch not detected", selector.matches(stringCollection), is(false));
		assertThat("None match not detected", selector.matches(stringCollection_alt), is(true));
		assertThat("None match not detected", selector.matches(stringCollection_empty), is(true));
		assertThat("None match not detected", selector.matches(nullValue), is(true));
	}

	@Test
	public void test_none_nocondition()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("none( \"attr\" )");
		// Act & Assert
		assertThat("None mismatch not detected", selector.matches(stringCollection), is(false));
		assertThat("None match not detected", selector.matches(stringCollection_empty), is(true));
		assertThat("None match not detected", selector.matches(nullValue), is(true));
	}

	@Test
	public void test_all()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("all( \"attr\", like( \"attr\", \"o\" ) )");
		// Act & Assert
		assertThat("All match not detected", selector.matches(stringCollection), is(true));
		assertThat("All mismatch not detected", selector.matches(stringCollection_alt), is(false));
		assertThat("All match not detected", selector.matches(stringCollection_empty), is(true));
		assertThat("All match not detected", selector.matches(nullValue), is(true));
	}

	@Test
	public void test_notall()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("notall( \"attr\", like( \"attr\", \"o\" ) )");
		// Act & Assert
		assertThat("Notall mismatch not detected", selector.matches(stringCollection), is(false));
		assertThat("Notall match not detected", selector.matches(stringCollection_alt), is(true));
		assertThat("Notall mismatch not detected", selector.matches(stringCollection_empty), is(false));
		assertThat("Notall mismatch not detected", selector.matches(nullValue), is(false));
	}

	@Test
	public void test_counting_selectors()
	{
		// Arrange
		var collection = new ProxyMock(Arrays.asList("Hello", "boys", "and", "girls!"));
		// Act & Assert
		assertThat("mt match not detected", CerberusLogic.compileSelector("mt( \"attr\", 3 )").matches(collection), is(true));
		assertThat("mt mismatch not detected", CerberusLogic.compileSelector("mt( \"attr\", 4 )").matches(collection), is(false));
		assertThat("mt match not detected", CerberusLogic.compileSelector("mt( \"attr\", 1, like( \"attr\", \"s\" ) )").matches(collection), is(true));
		assertThat("mt mismatch not detected", CerberusLogic.compileSelector("mt( \"attr\", 2, like( \"attr\", \"s\" ) )").matches(collection), is(false));

		assertThat("me match not detected", CerberusLogic.compileSelector("me( \"attr\", 3 )").matches(collection), is(true));
		assertThat("me match not detected", CerberusLogic.compileSelector("me( \"attr\", 4 )").matches(collection), is(true));
		assertThat("me mismatch not detected", CerberusLogic.compileSelector("me( \"attr\", 5 )").matches(collection), is(false));
		assertThat("me match not detected", CerberusLogic.compileSelector("me( \"attr\", 1, like( \"attr\", \"s\" ) )").matches(collection), is(true));
		assertThat("me match not detected", CerberusLogic.compileSelector("me( \"attr\", 2, like( \"attr\", \"s\" ) )").matches(collection), is(true));
		assertThat("me mismatch not detected", CerberusLogic.compileSelector("me( \"attr\", 3, like( \"attr\", \"s\" ) )").matches(collection), is(false));

		assertThat("ft match not detected", CerberusLogic.compileSelector("ft( \"attr\", 5 )").matches(collection), is(true));
		assertThat("ft mismatch not detected", CerberusLogic.compileSelector("ft( \"attr\", 4 )").matches(collection), is(false));
		assertThat("ft match not detected", CerberusLogic.compileSelector("ft( \"attr\", 3, like( \"attr\", \"s\" ) )").matches(collection), is(true));
		assertThat("ft mismatch not detected", CerberusLogic.compileSelector("ft( \"attr\", 2, like( \"attr\", \"s\" ) )").matches(collection), is(false));

		assertThat("fe match not detected", CerberusLogic.compileSelector("fe( \"attr\", 5 )").matches(collection), is(true));
		assertThat("fe match not detected", CerberusLogic.compileSelector("fe( \"attr\", 4 )").matches(collection), is(true));
		assertThat("fe mismatch not detected", CerberusLogic.compileSelector("fe( \"attr\", 3 )").matches(collection), is(false));
		assertThat("fe match not detected", CerberusLogic.compileSelector("fe( \"attr\", 3, like( \"attr\", \"s\" ) )").matches(collection), is(true));
		assertThat("fe match not detected", CerberusLogic.compileSelector("fe( \"attr\", 2, like( \"attr\", \"s\" ) )").matches(collection), is(true));
		assertThat("fe mismatch not detected", CerberusLogic.compileSelector("fe( \"attr\", 1, like( \"attr\", \"s\" ) )").matches(collection), is(false));

		assertThat("num match not detected", CerberusLogic.compileSelector("num( \"attr\", 4, 4 )").matches(collection), is(true));
		assertThat("num match not detected", CerberusLogic.compileSelector("num( \"attr\", 3, 5 )").matches(collection), is(true));
		assertThat("num mismatch not detected", CerberusLogic.compileSelector("num( \"attr\", 2, 3 )").matches(collection), is(false));
		assertThat("num mismatch not detected", CerberusLogic.compileSelector("num( \"attr\", 5, 6 )").matches(collection), is(false));
		assertThat("num match not detected", CerberusLogic.compileSelector("num( \"attr\", 2, 2, like( \"attr\", \"s\" ) )").matches(collection), is(true));
		assertThat("num match not detected", CerberusLogic.compileSelector("num( \"attr\", 1, 3, like( \"attr\", \"s\" ) )").matches(collection), is(true));
		assertThat("num mismatch not detected", CerberusLogic.compileSelector("num( \"attr\", 0, 1, like( \"attr\", \"s\" ) )").matches(collection), is(false));
		assertThat("num mismatch not detected", CerberusLogic.compileSelector("num( \"attr\", 3, 4, like( \"attr\", \"s\" ) )").matches(collection), is(false));

		assertThat("mt mismatch not detected", CerberusLogic.compileSelector("mt( \"attr\", ref( \"numAttr\" ) )").matches(collection), is(false));
		assertThat("me match not detected", CerberusLogic.compileSelector("me( \"attr\", ref( \"numAttr\" ) )").matches(collection), is(true));
		assertThat("ft mismatch not detected", CerberusLogic.compileSelector("ft( \"attr\", ref( \"numAttr\" ) )").matches(collection), is(false));
		assertThat("fe match not detected", CerberusLogic.compileSelector("fe( \"attr\", ref( \"numAttr\" ) )").matches(collection), is(true));
	}

	@Test
	public void test_and()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("and( like( \"attr\", \"Hello\" ), like( \"attr\", \"world\" ) )");
		// Act & Assert
		assertThat("And match not detected", selector.matches(stringScalar), is(true));
		assertThat("And mismatch not detected", selector.matches(stringScalar_alt), is(false));
		assertThat("And mismatch not detected", selector.matches(nullValue), is(false));
	}

	@Test
	public void test_nand()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("nand( like( \"attr\", \"Hello\" ), like( \"attr\", \"world\" ) )");
		// Act & Assert
		assertThat("Nand match not detected", selector.matches(stringScalar), is(false));
		assertThat("Nand mismatch not detected", selector.matches(stringScalar_alt), is(true));
		assertThat("Nand mismatch not detected", selector.matches(nullValue), is(true));
	}

	@Test
	public void test_not()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("not( like( \"attr\", \"world\" ) )");
		// Act & Assert
		assertThat("Not match not detected", selector.matches(stringScalar), is(false));
		assertThat("Not mismatch not detected", selector.matches(stringScalar_alt), is(true));
	}

	@Test
	public void test_or()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("or( like( \"attr\", \"hello\" ), like( \"attr\", \"world\" ) )");
		// Act & Assert
		assertThat("Or match not detected", selector.matches(stringScalar), is(true));
		assertThat("Or mismatch not detected", selector.matches(stringScalar_alt), is(false));
		assertThat("Or mismatch not detected", selector.matches(nullValue), is(false));
	}

	@Test
	public void test_nor()
	{
		// Arrange
		var selector = CerberusLogic.compileSelector("nor( like( \"attr\", \"hello\" ), like( \"attr\", \"world\" ) )");
		// Act & Assert
		assertThat("Nor match not detected", selector.matches(stringScalar), is(false));
		assertThat("Nor mismatch not detected", selector.matches(stringScalar_alt), is(true));
		assertThat("Nor mismatch not detected", selector.matches(nullValue), is(true));
	}

	@Test
	public void test_references()
	{
		// Arrange & Act & Assert
		assertThat("String match not detected", CerberusLogic.compileSelector("like( \"attr\", ref( \"altAttr\" ) )").matches(stringScalar), is(true));
		assertThat("String mismatch not detected", CerberusLogic.compileSelector("eq( \"attr\", ref( \"altAttr\" ) )").matches(stringScalar), is(false));
		assertThat("String match not detected", CerberusLogic.compileSelector("any( \"attr\", eq( \"attr\", ref( \"altAttr\" ) ) )").matches(stringCollection), is(true));
		assertThat("String mismatch not detected", CerberusLogic.compileSelector("all( \"attr\", eq( \"attr\", ref( \"altAttr\" ) ) )").matches(stringCollection), is(false));
	}

	@Test
	public void test_rule_selection()
	{
		// Arrange
		var ruleSet = CerberusLogic.compile("Rule( like( \"attr\", \"Hello\" ), like( \"attr\", \"world\" ) ).accept(); Rule( like( \"attr\", \"Hello\" ), like( \"attr\", \"guys\" ) ).reject();", "Rule");
		// Act & Assert
		assertThat("Incorrect rule selection", ruleSet.select(stringScalar).approve(nullValue).isAccept(), is(true));
		assertThat("Incorrect rule selection", ruleSet.select(stringScalar_alt).approve(nullValue).isAccept(), is(false));
		assertThat("Incorrect rule selection", ruleSet.select(nullValue), is(nullValue()));
	}

	@Test
	public void test_decision()
	{
		// Arrange
		var ruleSet = CerberusLogic.compile("Rule().accept( \"#00\", like( \"attr\", \"Hello\" ), like( \"attr\", \"world\" ) ).reject( \"#01\", like( \"attr\", \"Hello\" ), like( \"attr\", \"guys\" ) );", "Rule");
		// Act & Assert
		assertThat("Incorrect rule decision", ruleSet.approve(stringScalar).getActionId(), is("#00"));
		assertThat("Incorrect rule decision", ruleSet.approve(stringScalar_alt).getActionId(), is("#01"));
		assertThat("Incorrect rule decision", ruleSet.approve(nullValue), is(nullValue()));
	}

	@Test
	public void test_evaluate()
	{
		// Arrange
		var ruleSet = CerberusLogic.compile("Attribute( \"greetType\" ).accept( \"altAttr\", eq( \"attr\", \"Hello\" ) ).accept( \"numAttr\" ); Attribute( \"targetType\" ).accept( \"altAttr\", like( \"attr\", \"world\" ) ).accept( \"numAttr\" )", "Attribute");
		// Act & Assert
		assertThat("Incorrect computation", ruleSet.evaluate(stringScalar, false), is(Literals.asMap("greetType", "numAttr").add("targetType", "altAttr")));
		assertThat("Incorrect computation", ruleSet.evaluate(stringScalar, true), is(Literals.asMap("greetType", (Object) 4).add("targetType", "Hello")));
		assertThat("Incorrect computation", ruleSet.evaluate(stringScalar_alt, false), is(Literals.asMap("greetType", "numAttr").add("targetType", "numAttr")));
		assertThat("Incorrect computation", ruleSet.evaluate(stringScalar_alt, true), is(Literals.asMap("greetType", (Object) 4).add("targetType", 4)));
		assertThat("Incorrect computation", ruleSet.evaluate(stringScalar_alt_1, false), is(Literals.asMap("greetType", "altAttr").add("targetType", "numAttr")));
		assertThat("Incorrect computation", ruleSet.evaluate(stringScalar_alt_1, true), is(Literals.asMap("greetType", (Object) "Hello").add("targetType", 4)));
	}

	@Test
	public void test_config()
	{
		// Arrange
		var ruleSet = CerberusLogic.compile(String.format("config( stringParam( \"%s\" ), intParam( %s ), listParam( \"%s\", \"%s\" ), underscore_param() ); Rule().accept();", stringScalar.target, intScalar.target, stringScalar.target, stringScalar_alt.target ), null);
		var config = ruleSet.getConfig();
		// Act & Assert
		assertThat("Incorrect config value", config.get("ruleTag"), is("Rule"));
		assertThat("Incorrect config value", config.get("stringParam"), is(stringScalar.target));
		assertThat("Incorrect config value", config.get("intParam"), is(intScalar.target));
		assertThat("Incorrect config value", config.get("listParam"), is(Arrays.asList(stringScalar.target, stringScalar_alt.target)));
		assertThat("Incorrect config value", config.get("underscore_param"), is(nullValue()));
		assertThat("Incorrect rule decision", ruleSet.approve(nullValue).isAccept(), is(true));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void test_config_only_with_nested_map()
	{
		// Arrange
		var ruleSet = CerberusLogic.compile(String.format("config( map( stringParam( \"%s\" ), intParam( %s ), listParam( \"%s\", \"%s\" ), nullParam() ) )", stringScalar.target, intScalar.target, stringScalar.target, stringScalar_alt.target ), null);
		var config = ruleSet.getConfig();
		var parameterMap = (Map<String, Object>) config.get("map");
		// Act & Assert
		assertThat("Incorrect config value", parameterMap.get("stringParam"), is(stringScalar.target));
		assertThat("Incorrect config value", parameterMap.get("intParam"), is(intScalar.target));
		assertThat("Incorrect config value", parameterMap.get("listParam"), is(Arrays.asList(stringScalar.target, stringScalar_alt.target)));
		assertThat("Incorrect config value", parameterMap.get("nullParam"), is(nullValue()));
		assertThat("Incorrect rule decision", ruleSet.approve(nullValue), is(nullValue()));
	}

	@Test
	public void test_macros()
	{
		// Arrange
		var macros = Literals.asMap("outer", "$inner.head$( $inner.args$ )").add("inner.head", "like").add("inner.args", "\"attr\", \"Hello\"");
		var source = new StringBuilder("Rule().accept( \"#00\", $outer$ );");
		var expanded = "Rule().accept( \"#00\", like( \"attr\", \"Hello\" ) );";
		// Act
		var ruleSet = CerberusLogic.compile(source, null, macros);
		// Assert
		// Act & Assert
		assertThat("Incorrect expansion", source.toString(), is(expanded));
		assertThat("Incorrect rule decision", ruleSet.approve(stringScalar).getActionId(), is("#00"));
	}
}
