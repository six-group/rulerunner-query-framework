package com.six.iam.util;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import sailpoint.api.SailPointContext;
import sailpoint.object.*;
import sailpoint.tools.GeneralException;
import sailpoint.tools.Util;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;

public class OrionQLTest
{
	// scaffolding for parameterized attribute access test case

	// this is our parameter type hierarchy
	public interface IParameterBase {}
	public static class ParameterBaseClass implements IParameterBase {}
	public interface IParameter {}
	public static class ParameterClass extends ParameterBaseClass implements IParameter {}
	public static class ParameterOtherClass implements IParameter {}

	// this is the object we want to invoke parameterized getters on
	@SuppressWarnings("UnusedDeclaration")
	public static class TargetObject
	{
		// these helper methods return different types of objects to supply the getter parameters
		public Object getParameter()
		{
			return new ParameterClass();
		}
		public Object getBaseParameter()
		{
			return new ParameterBaseClass();
		}
		public Object getOtherParameter()
		{
			return new ParameterOtherClass();
		}
		public Object getObject()
		{
			return new Object();
		}

		// for these methods we are going to test the dispatching by name and parameter type
		public String getParameterValue(ParameterClass parameter)
		{
			return "getParameterValue(ParameterClass): " + parameter.getClass().getName();
		}
		public String getIParameterValue(IParameter parameter)
		{
			return "getIParameterValue(IParameter): " + parameter.getClass().getName();
		}
		public String getBaseParameterValue(ParameterBaseClass parameter)
		{
			return "getBaseParameterValue(ParameterBaseClass): " + parameter.getClass().getName();
		}
		public String getIBaseParameterValue(IParameterBase parameter)
		{
			return "getIBaseParameterValue(IParameterBase): " + parameter.getClass().getName();
		}
		public String getOverride(ParameterClass parameter)
		{
			return "getOverride(ParameterClass): " + parameter.getClass().getName();
		}
		public String getOverride(ParameterBaseClass parameter)
		{
			return "getOverride(ParameterBaseClass): " + parameter.getClass().getName();
		}
		public String getOverride(IParameter parameter)
		{
			return "getOverride(IParameter): " + parameter.getClass().getName();
		}
		public String getOverride(IParameterBase parameter)
		{
			return "never called";
		}
		public String getOverride(Object parameter)
		{
			return "getOverride(Object): " + parameter.getClass().getName();
		}
	}
	// end of scaffolding for parameterized attribute access test case

	private final Identity manager = createIdentity("tk000", "Hans", "Muster", "CIT", null, null);
	private final Bundle bundle_AR_10000029 = createBundle("AR_10000029", "Archiv Storagesystem Prod - Server Access-Applikationsadmin Archiv (ux)", "Archiv", null);
	private final Bundle bundle_AR_10000044 = createBundle("AR_10000044", "FIMS QA - Server Access-Applikationsadmin FIMS (ux)", "FIMS", null);
	private final Bundle bundle_AR_10000060 = createBundle("AR_10000060", "fintop-Dev Server Access-Applikationsadmin Fintop (ux)", "Fintop", manager);
	private final Identity identity = createIdentity("tk001", "John", "Doe", "CIT-DOE", manager, List.of(bundle_AR_10000029, bundle_AR_10000044, bundle_AR_10000060));
	private final Identity subordinate = createIdentity("tk002", "Calpurnius", "Blandus", "CIT-DOE", identity, List.of(bundle_AR_10000029, bundle_AR_10000060));
	private final Bundle bundle = createBundle("AR_10000111", "ControlM Prod - Server Access Applikationsadmin Control-M (ux)", "ControlM", identity);

	private static Bundle createBundle(String name, String displayName, String application, Identity owner)
	{
		var result = new Bundle();
		result.setName(name);
		result.setDisplayName(displayName);
		result.setAttribute("application.name", application);
		result.setOwner(owner);
		result.addDescription("en_US", String.format("%s (%s)", displayName, name));
		return result;
	}

	@SuppressWarnings("MethodWithTooManyParameters")
	private static Identity createIdentity(String name, String firstname, String lastname, String orgUnit, Identity manager, List<Bundle> roles)
	{
		var result = new Identity();
		result.setCreated(Literals.asDate("2022-05-31 10:30:01"));
		result.setName(name);
		result.setFirstname(firstname);
		result.setLastname(lastname);
		result.setAttribute("orgUnit", orgUnit);
		result.setAttribute("orgUnit.id", String.format("#%s", orgUnit));
		result.setManager(manager);
		if (roles != null) {
			result.setAssignedRoles(roles);
		}
		return result;
	}

	@Test
	public void test_whitespace()
	{
		// Arrange
		var input = identity;
		// Act & Assert
		assertThat("Incorrect result", OrionQL.evaluate(input, "-- read'em;assignedRoles\\\n-- map'em\n:map(\\\n  name\\\n) -- done", null), is(List.of("AR_10000029", "AR_10000044", "AR_10000060")));
		assertThat("Incorrect result", OrionQL.evaluate(input, "-- some comment\n  \"-- null --\"", null), is("-- null --"));
		assertThat("Incorrect result", OrionQL.evaluate(input, "-- some comment\n  -42", null), is(-42));
		assertThat("Incorrect result", OrionQL.format(input, "${firstname} \\\n  ${lastname}", null), is("John Doe"));
		assertThat("Incorrect result", OrionQL.evaluateAll(OrionQL.compileAll("-- get firstname\nfirstname,\\\n-- get lastname\nlast name=-- use lastname;lastname --", ",", null, false), OrionQL.wrap(input, null)), is(Literals.asMap("firstname", "John").add("last name", "Doe")));
	}

	@Test
	public void test_expression_assignment_syntax()
	{
		// Arrange
		var accessors = OrionQL.compileAll("foo=a\n:replace(=,)\n*=b\nc\\,d=\ne,f=h", "\n", null, false);
		// Act & Assert
		assertThat("Incorrect key", accessors.get(0).getKey(), is("foo"));
		assertThat("Incorrect key", accessors.get(1).getKey(), is(nullValue()));
		assertThat("Incorrect key", accessors.get(2).getKey(), is("*"));
		assertThat("Incorrect key", accessors.get(3).getKey(), is("c,d"));
		assertThat("Incorrect key", accessors.get(4).getKey(), is(List.of("e", "f")));
		assertThat("Incorrect key", accessors.get(0).hasKey(), is(true));
		assertThat("Incorrect name", accessors.get(0).getName(), is("foo"));
		assertThat("Incorrect expression", accessors.get(0).getExpression(), is("a"));
		assertThat("Incorrect key", accessors.get(1).hasKey(), is(false));
		assertThat("Incorrect name", accessors.get(1).getName(), is(":replace(=,)"));
	}

	@Test
	public void test_template_assignment_syntax()
	{
		// Arrange
		var accessors = OrionQL.compileAll("foo=${a}\n${:replace(=,)}\n*=${b}\nc\\,d=\ne,f=${h}", "\n", null, true);
		// Act & Assert
		assertThat("Incorrect key", accessors.get(0).getKey(), is("foo"));
		assertThat("Incorrect key", accessors.get(1).getKey(), is(nullValue()));
		assertThat("Incorrect key", accessors.get(2).getKey(), is("*"));
		assertThat("Incorrect key", accessors.get(3).getKey(), is("c,d"));
		assertThat("Incorrect key", accessors.get(4).getKey(), is(List.of("e", "f")));
		assertThat("Incorrect key", accessors.get(0).hasKey(), is(true));
		assertThat("Incorrect name", accessors.get(0).getName(), is("foo"));
		assertThat("Incorrect expression", accessors.get(0).getExpression(), is("${a}"));
		assertThat("Incorrect key", accessors.get(1).hasKey(), is(false));
		assertThat("Incorrect name", accessors.get(1).getName(), is("${:replace(=,)}"));
	}

	@Test
	public void test_convenience_methods()
	{
		// Arrange
		var input = identity;
		// Act & Assert
		assertThat("Incorrect result", OrionQL.evaluate(input, "assignedRoles:map(name)", null), is(List.of("AR_10000029", "AR_10000044", "AR_10000060")));
		assertThat("Incorrect result", OrionQL.format(input, "${firstname} ${lastname} (${name})", null), is("John Doe (tk001)"));
		assertThat("Incorrect result", OrionQL.evaluateAll(OrionQL.compileAll("firstname,lastname", ",", false, false), OrionQL.wrap(input, null)), is(Literals.asMap("firstname", "John").add("lastname", "Doe")));
	}

	@Test
	public void test_type_guess()
	{
		// Arrange
		var macros = Literals.asMap(
				"managerName", "manager.name"
		).add(
				"identityFormat", "${displayName} (${name})"
		).add(
				"formatIdentity", ":format(${displayName} (${name}))"
		).add(
				"applicationFormat", "${application.name}"
		);
		// Act & Assert
		assertThat("Incorrect guess", OrionQL.guessInputType("manager.name", macros), is(false));
		assertThat("Incorrect guess", OrionQL.guessInputType("$managerName$", macros), is(false));
		assertThat("Incorrect guess", OrionQL.guessInputType(":format(${displayName} (${name})", macros), is(false));
		assertThat("Incorrect guess", OrionQL.guessInputType("$formatIdentity$", macros), is(false));
		assertThat("Incorrect guess", OrionQL.guessInputType("${displayName} (${name})", macros), is(true));
		assertThat("Incorrect guess", OrionQL.guessInputType("$identityFormat$", macros), is(true));
		assertThat("Incorrect guess", OrionQL.guessInputType("[${application.name}] ${nativeIdentity}", macros), is(true));
		assertThat("Incorrect guess", OrionQL.guessInputType("[$applicationFormat$] ${nativeIdentity}", macros), is(true));
	}

	@Test
	public void test_environment()
	{
		// Arrange
		var env = Literals.asMap("hostname", (Object) "localhost").add("username", "spadmin");
		var proxy = OrionQL.wrap(null, null, null, env);
		// Act & Assert
		assertThat("Incorrect environment", proxy.access(":env"), is(env));
		assertThat("Incorrect environment", proxy.access(":env(hostname)"), is(env.get("hostname")));
		assertThat("Incorrect environment", proxy.access(":env(username)"), is(env.get("username")));
	}

	@Test
	public void test_expression_macros()
	{
		// Arrange
		var macros = Literals.asMap(
				"managerRolesList", "$managerRoles$.$formatRoles$"
		).add(
				"managerRoles", "manager.assignedRoles"
		).add(
				"formatRoles", ":map(:format($roleFormat$))"
		).add(
				"roleFormat", "${displayName} (${name})"
		).add(
				"twentyseven", "27"
		);
		var proxy = OrionQL.wrap(subordinate, null, macros, null);
		// Act & Assert
		assertThat("Incorrect result", proxy.access("$managerRolesList$:join"), is("Archiv Storagesystem Prod - Server Access-Applikationsadmin Archiv (ux) (AR_10000029)\nFIMS QA - Server Access-Applikationsadmin FIMS (ux) (AR_10000044)\nfintop-Dev Server Access-Applikationsadmin Fintop (ux) (AR_10000060)"));
		assertThat("Incorrect result", proxy.access("$twentyseven$-($twentyseven$+$twentyseven$)"), is(-27));
	}

	@Test
	public void test_template_macros()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null);
		var macros = Literals.asMap("label", "$displayname$, \\\n  ${orgUnit}").add("displayname", "${firstname} ${lastname}").add("rolesLabel", "roles");
		var template = new StringBuilder("$label$: ${assignedRoles:size} $rolesLabel$");
		var accessor = OrionQL.compile(template, true, macros);
		// Act & Assert
		assertThat("Incorrect macro expansion", template.toString(), is("${firstname} ${lastname}, ${orgUnit}: ${assignedRoles:size} roles"));
		assertThat("Incorrect result", accessor.access(proxy), is("John Doe, CIT-DOE: 3 roles"));
	}

	@Test
	public void test_expression_macros_multicolumn()
	{
		// Arrange
		var macros = Literals.asMap(
				"columns", "$roleColumns$,owner=owner:format($label$)"
		).add(
				"roleColumns", "id=name,name=$displayname$"
		).add(
				"displayname", "displayName"
		).add(
				"label", "${$displayname$} (${name})"
		);
		var proxy = OrionQL.wrap(identity, null, macros, null);
		// Act & Assert
		assertThat("Incorrect result", proxy.access("assignedRoles:table($columns$,nameLength=$displayname$:size)").toString(), is("<table><thead><tr><th>id</th><th>name</th><th>owner</th><th>nameLength</th></tr></thead><tbody><tr><td>AR_10000029</td><td>Archiv Storagesystem Prod - Server Access-Applikationsadmin Archiv (ux)</td><td></td><td>71</td></tr><tr><td>AR_10000044</td><td>FIMS QA - Server Access-Applikationsadmin FIMS (ux)</td><td></td><td>51</td></tr><tr><td>AR_10000060</td><td>fintop-Dev Server Access-Applikationsadmin Fintop (ux)</td><td>Hans Muster (tk000)</td><td>54</td></tr></tbody></table>"));
	}

	@Test
	public void test_template_macros_multicolumn() throws IOException
	{
		// Arrange
		var macros = Literals.asMap(
				"columns", "$roleColumns$,owner=${owner:format($label$),}"
		).add(
				"roleColumns", "id=${name},name=$displayname$"
		).add(
				"displayname", "${displayName}"
		).add(
				"displaynamesize", "${displayName:size}"
		).add(
				"label", "$displayname$ (${name})"
		);
		var proxy = OrionQL.wrap(identity.getAssignedRoles().iterator(), null);
		var writer = new TabularData(OrionQL.compileAll("$columns$,nameLength=$displaynamesize$", ",", null, true, macros));
		// Act & Assert
		var output = new ByteArrayOutputStream();
		writer.setOutputStream(output, "UTF-8");
		writer.writeHtmlTable(proxy);
		assertThat("Incorrect result", output.toString(), is("<table><thead><tr><th>id</th><th>name</th><th>owner</th><th>nameLength</th></tr></thead><tbody><tr><td>AR_10000029</td><td>Archiv Storagesystem Prod - Server Access-Applikationsadmin Archiv (ux)</td><td></td><td>71</td></tr><tr><td>AR_10000044</td><td>FIMS QA - Server Access-Applikationsadmin FIMS (ux)</td><td></td><td>51</td></tr><tr><td>AR_10000060</td><td>fintop-Dev Server Access-Applikationsadmin Fintop (ux)</td><td>Hans Muster (tk000)</td><td>54</td></tr></tbody></table>"));
	}

	@SuppressWarnings("MagicNumber")
	@Test
	public void test_literal()
	{
		// Arrange
		var proxy = OrionQL.wrap(null, null).implant("false", "Hello!");
		// Act & Assert
		assertThat("Incorrect literal", proxy.access("\"\""), is(""));
		assertThat("Incorrect literal", proxy.access("\"foo\""), is("foo"));
		assertThat("Incorrect literal", proxy.access("\"foo.bar\""), is("foo.bar"));
		assertThat("Incorrect literal", proxy.access("\"foo:\\\"bar\\\"\""), is("foo:\"bar\""));
		assertThat("Incorrect literal", proxy.access("\"-- null --\""), is("-- null --"));
		assertThat("Incorrect literal", proxy.access("42"), is(42));
		assertThat("Incorrect literal", proxy.access("-42"), is(-42));
		assertThat("Incorrect literal", proxy.access("+4.2"), is(4.2));
		assertThat("Incorrect literal", proxy.access("42L"), is(42L));
		assertThat("Incorrect literal", proxy.access("1m15s"), is(75000L));
		assertThat("Incorrect literal", proxy.access("true"), is(true));
		assertThat("Incorrect literal", proxy.access("false"), is(false));
		assertThat("Incorrect literal", proxy.access("null"), is(nullValue()));
		assertThat("Incorrect attribute value", proxy.access(".false"), is("Hello!"));
	}

	@Test
	public void test_attribute_access()
	{
		// Arrange
		var proxy = OrionQL.wrap(bundle, null);
		// Act & Assert
		// - simple attribute access (test autocast also)
		assertThat("Incorrect attribute value", proxy.access("name"), is(bundle.getName()));
		// - chained attribute access
		assertThat("Incorrect attribute value", proxy.access("owner.manager.name"), is(bundle.getOwner().getManager().getName()));
		assertThat("Incorrect attribute value", proxy.access("approver.name"), is(nullValue()));
		// - extended attribute access
		assertThat("Incorrect attribute value", proxy.access("owner.orgUnit"), is(bundle.getOwner().getAttribute("orgUnit")));
		// - special extended attribute syntax
		assertThat("Incorrect attribute value", proxy.access("owner.orgUnit\\.id"), is(bundle.getOwner().getAttribute("orgUnit.id")));
		// - map access
		assertThat("Incorrect attribute value", proxy.access("owner.attributes.orgUnit"), is(bundle.getOwner().getAttribute("orgUnit")));
	}

	@Test
	public void test_tolerant_attribute_access()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null);
		// Act & Assert
		assertThat("Incorrect access", proxy.access("name.foo?"), is(nullValue()));
		assertThat("Incorrect access", proxy.access("foo?(1)"), is(nullValue()));
		assertThat("Incorrect access", proxy.access("^?"), is(nullValue()));
		assertThat("Incorrect access", proxy.access("assignedRoles:map(^Application?):join").toString(), is("\n\n"));
	}

	@Test
	public void test_parameterized_attribute_access()
	{
		// Arrange
		var proxy = OrionQL.wrap(bundle, null);
		proxy.implant("attributeName", "orgUnit");
		// Act & Assert
		assertThat("Incorrect attribute value", proxy.access("owner.attribute(:format(orgUnit))"), is("CIT-DOE"));
		assertThat("Incorrect attribute value", proxy.access("owner.attribute(attributeName)"), is("CIT-DOE"));
		assertThat("Incorrect attribute value", proxy.access("description(:format(en_US))"), is("ControlM Prod - Server Access Applikationsadmin Control-M (ux) (AR_10000111)"));
	}

	@Test
	public void test_accessor_chain()
	{
		// Arrange
		var proxy = OrionQL.wrap("Hello", null);
		proxy.implant("motd", "Life is beautiful");
		// Act & Assert
		assertThat("Incorrect chain", proxy.access("motd:match(is):format(${})"), is("is"));
		assertThat("Incorrect chain", proxy.access("motd:match(it):format(${})"), is(nullValue()));
		assertThat("Incorrect chain", proxy.access("\"Do it now!\":match(it):format(${})"), is("it"));
		assertThat("Incorrect chain", proxy.access("\"Do it now!\":match(it):format(${@}: ${})"), is("Hello: it"));
		assertThat("Incorrect chain", proxy.access("\"Do it now!\":match(it):format(${@.motd}: ${})"), is("Life is beautiful: it"));
	}

	@Test
	public void test_parameterized_attribute_access_inheritance()
	{
		// Arrange
		var proxy = OrionQL.wrap(new TargetObject(), null);
		// Act & Assert
		assertThat("Incorrect attribute value", proxy.access("parameterValue(parameter)"), is("getParameterValue(ParameterClass): com.six.iam.util.OrionQLTest$ParameterClass"));
		assertThat("Incorrect attribute value", proxy.access("iParameterValue(parameter)"), is("getIParameterValue(IParameter): com.six.iam.util.OrionQLTest$ParameterClass"));
		assertThat("Incorrect attribute value", proxy.access("baseParameterValue(parameter)"), is("getBaseParameterValue(ParameterBaseClass): com.six.iam.util.OrionQLTest$ParameterClass"));
		assertThat("Incorrect attribute value", proxy.access("iBaseParameterValue(parameter)"), is("getIBaseParameterValue(IParameterBase): com.six.iam.util.OrionQLTest$ParameterClass"));
		assertThat("Incorrect attribute value", proxy.access("baseParameterValue(baseParameter)"), is("getBaseParameterValue(ParameterBaseClass): com.six.iam.util.OrionQLTest$ParameterBaseClass"));
		assertThat("Incorrect attribute value", proxy.access("iBaseParameterValue(baseParameter)"), is("getIBaseParameterValue(IParameterBase): com.six.iam.util.OrionQLTest$ParameterBaseClass"));
		assertThat("Incorrect attribute value", proxy.access("iParameterValue(otherParameter)"), is("getIParameterValue(IParameter): com.six.iam.util.OrionQLTest$ParameterOtherClass"));
		assertThat("Incorrect attribute value", proxy.access("override(parameter)"), is("getOverride(ParameterClass): com.six.iam.util.OrionQLTest$ParameterClass"));
		assertThat("Incorrect attribute value", proxy.access("override(baseParameter)"), is("getOverride(ParameterBaseClass): com.six.iam.util.OrionQLTest$ParameterBaseClass"));
		assertThat("Incorrect attribute value", proxy.access("override(otherParameter)"), is("getOverride(IParameter): com.six.iam.util.OrionQLTest$ParameterOtherClass"));
		assertThat("Incorrect attribute value", proxy.access("override(object)"), is("getOverride(Object): java.lang.Object"));
	}

	@Test
	public void test_implant()
	{
		// Arrange
		var proxy = OrionQL.wrap(bundle, null);
		proxy.implant("approver", identity);
		// Act & Assert
		assertThat("Incorrect attribute value", proxy.access("name"), is(bundle.getName()));
		assertThat("Incorrect attribute value", proxy.access("approver.name"), is(identity.getName()));
	}

	@Test
	public void test_choice()
	{
		// Arrange
		var identityProxy = OrionQL.wrap(identity, null);
		var nullProxy = OrionQL.wrap(null, null);
		// Act & Assert
		assertThat("Incorrect choice", identityProxy.access("logonid"), is(nullValue()));
		assertThat("Incorrect choice", identityProxy.access("logonid|displayName"), is("John Doe"));
		assertThat("Incorrect choice", identityProxy.access("displayName|logonid"), is("John Doe"));
		assertThat("Incorrect choice", identityProxy.access("displayName"), is("John Doe"));
		assertThat("Incorrect choice", nullProxy.access("@|1234"), is(1234));
	}

	@Test
	public void test_postprocessing()
	{
		// Arrange
		var identityProxy = OrionQL.wrap(identity, null);
		var bundleProxy = OrionQL.wrap(bundle, null);
		// Act & Assert
		assertThat("Incorrect postprocessing result", identityProxy.access(":format(${firstname} ${lastname})"), is("John Doe"));
		assertThat("Incorrect postprocessing result", bundleProxy.access("owner:format(${firstname} ${lastname})"), is("John Doe"));
		assertThat("Incorrect postprocessing result", bundleProxy.access(":format(${owner.firstname} ${owner.lastname})"), is("John Doe"));
	}

	@Test
	public void test_formatting()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null);
		proxy.implant("startDate", Literals.asDate("2022-04-07 16:49:13"));
		// Act & Assert
		assertThat("Incorrect formatting result", proxy.format("${displayableName|:format(${lastname}, ${firstname})}"), is("John Doe"));
		assertThat("Incorrect formatting result", proxy.access(":format(${firstname} ${lastname} (${name}))"), is("John Doe (tk001)"));
		assertThat("Incorrect formatting result", proxy.access(":format(${firstname} ${lastname} \\(${name})"), is("John Doe (tk001"));
		assertThat("Incorrect formatting result", proxy.access(":format(${firstname} ${lastname} \\(${name}\\))"), is("John Doe (tk001)"));
		assertThat("Incorrect formatting result", proxy.access(":format(${firstname} ${lastname} ((${name}), \"1) \\\"foo\\\", 2)\\\"bar\\\"\"))"), is("John Doe ((tk001), \"1) \\\"foo\\\", 2)\\\"bar\\\"\")"));
		assertThat("Incorrect formatting result", proxy.access(":format(${firstname} ${lastname}, 1\\) foo, 2\\) bar)"), is("John Doe, 1) foo, 2) bar"));
		assertThat("Incorrect formatting result", proxy.access(":format(${displayableName|:format(${lastname}, ${firstname})})"), is("John Doe"));
		assertThat("Incorrect formatting result", proxy.access(":format(${logonid|:format(${lastname}, ${firstname})})"), is("Doe, John"));
		assertThat("Incorrect formatting result", proxy.access(":format(${logonid,--unknown--})"), is("--unknown--"));
		assertThat("Incorrect formatting result", proxy.access(":format(${%20s,name,--unknown--})"), is("               tk001"));
		assertThat("Incorrect date formatting", proxy.access(":format(${startDate})"), is("2022-04-07 16:49:13"));
		assertThat("Incorrect date formatting", proxy.access(":format(${%yyyy-MM-dd HH:mm:ss,startDate})"), is("2022-04-07 16:49:13"));
		assertThat("Incorrect date formatting", proxy.access(":format(${%tF,startDate})"), is("2022-04-07"));
		assertThat("Incorrect date formatting", proxy.access(":format(${%tT,startDate})"), is("16:49:13"));
		assertThat("Incorrect date formatting", proxy.access(":format(${%%tF %1$tT,startDate})"), is("2022-04-07 16:49:13"));
		assertThat("Incorrect date formatting", proxy.access("startDate:format(${%tT,})"), is("16:49:13"));
		assertThat("Incorrect date formatting", proxy.access("startDate:format(${%tT})"), is("16:49:13"));
		assertThat("Incorrect date formatting", proxy.access("endDate:format(${%tT})"), is(nullValue()));
		assertThat("Incorrect date formatting", proxy.access(":format(${endDate})"), is("null"));
		assertThat("Incorrect date formatting", proxy.access(":format(${%tT,endDate})"), is("null"));
		assertThat("Incorrect date formatting", proxy.access(":format(${%tT,endDate,})"), is(""));
		assertThat("Incorrect list formatting", proxy.access("assignedRoles:map(name):format($\"\")"), is("{\"AR_10000029\", \"AR_10000044\", \"AR_10000060\"}"));
		assertThat("Incorrect formatting result", proxy.access(":format(${%[%s] ,logonid,})"), is(""));
		assertThat("Incorrect formatting result", proxy.access(":format(${%[%s] ,name,})"), is("[tk001] "));
		assertThat("Incorrect date formatting", proxy.access(":format(${%'['yyyy-MM-dd HH:mm:ss']: ',startDate})"), is("[2022-04-07 16:49:13]: "));
		assertThat("Incorrect date formatting", proxy.access(":format(${%[%tF %1$tT]: ,startDate})"), is("[2022-04-07 16:49:13]: "));
	}

	@Test
	public void test_quoting()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null);
		// lastname <- Doe "Master"\One
		proxy.implant("lastname", "Doe \"Master\"\\One");
		proxy.implant("startDate", Literals.asDate("2022-04-07 16:49:13"));
		// Act & Assert
		// firstname=John
		assertThat("Incorrect formatting", proxy.format("firstname=${firstname}"), is("firstname=John"));
		// firstname="John"
		assertThat("Incorrect quoting", proxy.format("firstname=$\"firstname\""), is("firstname=\"John\""));
		// lastname=Doe "Master"\One
		assertThat("Incorrect formatting", proxy.format("lastname=${lastname}"), is("lastname=Doe \"Master\"\\One"));
		// lastname="Doe \"Master\"\\One"
		assertThat("Incorrect quoting", proxy.format("lastname=$\"lastname\""), is("lastname=\"Doe \\\"Master\\\"\\\\One\""));
		assertThat("Incorrect quoting", proxy.format("created < $\"startDate\""), is("created < DATE$1649342953000"));
		assertThat("Incorrect quoting", proxy.format("$\"%yyyy-MM-dd HH:mm:ss,startDate\""), is("\"2022-04-07 16:49:13\""));
	}

	@Test
	public void test_html()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null);
		proxy.implant("motd", "Life is -> beautiful");
		// Act & Assert
		assertThat("Incorrect HTML", proxy.access(":link(/identity/${name},${displayName})").toString(), is("<a href=\"/identity/tk001\" target=\"_blank\">John Doe</a>"));
		assertThat("Incorrect HTML", proxy.access(":link(#,${displayName},,red)").toString(), is("<span style=\"color: red;\">John Doe</span>"));
		assertThat("Incorrect HTML", proxy.access(":link(#,${displayName},,background-color: red;)").toString(), is("<span style=\"background-color: red;\">John Doe</span>"));
		assertThat("Incorrect HTML", proxy.access(":link(/identity/${name},${displayName},<h1>${motd}</h1>)").toString().replaceAll("\"[0-9]+,[-0-9]+\"", "\"nnnn,nnnn\""), is("<span class=\"pinnable-tooltip-container\"><a href=\"/identity/tk001\" target=\"_blank\">John Doe</a><input type=\"checkbox\" id=\"nnnn,nnnn\"><label for=\"nnnn,nnnn\"></label><div class=\"pinnable-tooltip-content\"><h1>Life is -&gt; beautiful</h1></div></span>"));
		assertThat("Incorrect HTML", proxy.access(":link(#,${displayName},<h1>${motd}</h1>)").toString().replaceAll("\"[0-9]+,[-0-9]+\"", "\"nnnn,nnnn\""), is("<span class=\"pinnable-tooltip-container\"><span>John Doe</span><input type=\"checkbox\" id=\"nnnn,nnnn\"><label for=\"nnnn,nnnn\"></label><div class=\"pinnable-tooltip-content\"><h1>Life is -&gt; beautiful</h1></div></span>"));
		assertThat("Incorrect HTML", proxy.access(":link(-,${displayName},<h1>${motd}</h1>)").toString().replaceAll("\"[0-9]+,[-0-9]+\"", "\"nnnn,nnnn\""), is("<span class=\"pinnable-tooltip-container\"><label for=\"nnnn,nnnn\">John Doe</label><input type=\"checkbox\" id=\"nnnn,nnnn\"><div class=\"pinnable-tooltip-content\"><h1>Life is -&gt; beautiful</h1></div></span>"));
		assertThat("Incorrect HTML", proxy.access(":link(-,${displayName},<h1>${motd}</h1>,#ff0000)").toString().replaceAll("\"[0-9]+,[-0-9]+\"", "\"nnnn,nnnn\""), is("<span class=\"pinnable-tooltip-container\"><label style=\"color: #ff0000;\" for=\"nnnn,nnnn\">John Doe</label><input type=\"checkbox\" id=\"nnnn,nnnn\"><div class=\"pinnable-tooltip-content\"><h1>Life is -&gt; beautiful</h1></div></span>"));
		assertThat("Incorrect HTML", proxy.access(":link(-,${displayName},<h1>${motd}</h1>,${50:heat})").toString().replaceAll("\"[0-9]+,[-0-9]+\"", "\"nnnn,nnnn\""), is("<span class=\"pinnable-tooltip-container\"><label style=\"color: #7f007f;\" for=\"nnnn,nnnn\">John Doe</label><input type=\"checkbox\" id=\"nnnn,nnnn\"><div class=\"pinnable-tooltip-content\"><h1>Life is -&gt; beautiful</h1></div></span>"));
		assertThat("Incorrect HTML", proxy.access(":link(-,${displayName},<h1>${motd}</h1>,background-color: ${50:heat};)").toString().replaceAll("\"[0-9]+,[-0-9]+\"", "\"nnnn,nnnn\""), is("<span class=\"pinnable-tooltip-container\"><label style=\"background-color: #7f007f;\" for=\"nnnn,nnnn\">John Doe</label><input type=\"checkbox\" id=\"nnnn,nnnn\"><div class=\"pinnable-tooltip-content\"><h1>Life is -&gt; beautiful</h1></div></span>"));
		assertThat("Incorrect HTML", proxy.access("0:heat").toString(), is("#0000ff"));
		assertThat("Incorrect HTML", proxy.access("100:heat").toString(), is("#ff0000"));
		assertThat("Incorrect HTML", proxy.access("50:heat").toString(), is("#7f007f"));
		assertThat("Incorrect HTML", proxy.access("50:heat(#00ff00)").toString(), is("#7f7f00"));
		assertThat("Incorrect HTML", proxy.access("500:heat(#00ff00,#ff0000=1000)").toString(), is("#7f7f00"));
		assertThat("Incorrect HTML", proxy.access("50:heat(#0000ff,#00ff00,#ff0000)").toString(), is("#007f7f"));
		assertThat("Incorrect HTML", proxy.access("100:heat(#0000ff,#00ff00,#ff0000)").toString(), is("#00ff00"));
		assertThat("Incorrect HTML", proxy.access("150:heat(#0000ff,#00ff00,#ff0000)").toString(), is("#7f7f00"));
		assertThat("Incorrect HTML", proxy.access("600:heat(#0000ff,#00ff00,#ff0000=1000)").toString(), is("#7f7f00"));
		assertThat("Incorrect HTML", proxy.access("\"-1\":heat").toString(), is("#808080"));
		assertThat("Incorrect HTML", proxy.access("101:heat").toString(), is("#c0c0c0"));
		assertThat("Incorrect HTML", proxy.access(":html(<div>${:link(/identity/${name},${displayName})}: ${motd}</div>)").toString(), is("<div><a href=\"/identity/tk001\" target=\"_blank\">John Doe</a>: Life is -&gt; beautiful</div>"));
		assertThat("Incorrect HTML", proxy.access("@:html(<div>${:link(/identity/${name},${displayName})}: ${motd}</div>)").getClass().getSimpleName(), is("HtmlContainer"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:map(:link(/bundle/${name},${displayName})):join").toString(), is("<a href=\"/bundle/AR_10000029\" target=\"_blank\">Archiv Storagesystem Prod - Server Access-Applikationsadmin Archiv (ux)</a>\n<a href=\"/bundle/AR_10000044\" target=\"_blank\">FIMS QA - Server Access-Applikationsadmin FIMS (ux)</a>\n<a href=\"/bundle/AR_10000060\" target=\"_blank\">fintop-Dev Server Access-Applikationsadmin Fintop (ux)</a>"));
	}

	@Test
	public void test_html_with_environment()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null, null, Literals.asMap("baseurl", "https://myaccess.sn.six-group.net/identityiq/rulerunner/"));
		// Act & Assert
		assertThat("Incorrect HTML", proxy.access(":link(identity/${name},${displayName})").toString(), is("<a href=\"https://myaccess.sn.six-group.net/identityiq/rulerunner/identity/tk001\" target=\"_blank\">John Doe</a>"));
		assertThat("Incorrect HTML", proxy.access(":link(/identity/${name},${displayName})").toString(), is("<a href=\"https://myaccess.sn.six-group.net/identity/tk001\" target=\"_blank\">John Doe</a>"));
		assertThat("Incorrect HTML", proxy.access(":link(https://myaccess-int.sn.six-group.net/identity/${name},${displayName})").toString(), is("<a href=\"https://myaccess-int.sn.six-group.net/identity/tk001\" target=\"_blank\">John Doe</a>"));
	}

	@Test
	public void test_html_table_grid()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null);
		// Act & Assert
		assertThat("Incorrect HTML", proxy.access("assignedRoles:table(identity=^.name,name,displayName)").toString(), is("<table><thead><tr><th>identity</th><th>name</th><th>displayName</th></tr></thead><tbody><tr><td>tk001</td><td>AR_10000029</td><td>Archiv Storagesystem Prod - Server Access-Applikationsadmin Archiv (ux)</td></tr><tr><td>tk001</td><td>AR_10000044</td><td>FIMS QA - Server Access-Applikationsadmin FIMS (ux)</td></tr><tr><td>tk001</td><td>AR_10000060</td><td>fintop-Dev Server Access-Applikationsadmin Fintop (ux)</td></tr></tbody></table>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:table(identity=^.name,name,displayName)").getClass().getSimpleName(), is("HtmlContainer"));
		assertThat("Incorrect HTML", proxy.access(":htable(name,displayName,assignedRoles:size)").toString(), is("<table><tbody><tr><th>name</th><td>tk001</td></tr><tr><th>displayName</th><td>John Doe</td></tr><tr><th>assignedRoles:size</th><td>3</td></tr></tbody></table>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:index(name#^.name,\"*\"):grid").toString(), is("<table><thead><tr><th></th><th>tk001</th></tr></thead><tbody><tr><th>AR_10000029</th><td>*</td></tr><tr><th>AR_10000060</th><td>*</td></tr><tr><th>AR_10000044</th><td>*</td></tr></tbody></table>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:index(name#^.name,1):grid").toString(), is("<table><thead><tr><th></th><th>tk001</th></tr></thead><tbody><tr><th>AR_10000029</th><td class=\"numeric\">1</td></tr><tr><th>AR_10000060</th><td class=\"numeric\">1</td></tr><tr><th>AR_10000044</th><td class=\"numeric\">1</td></tr><tr><th>TOTAL</th><td class=\"numeric\">3</td></tr></tbody></table>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:index(name#^.name,1):grid()").toString(), is("<table><thead><tr><th></th><th>tk001</th></tr></thead><tbody><tr><th>AR_10000029</th><td>1</td></tr><tr><th>AR_10000060</th><td>1</td></tr><tr><th>AR_10000044</th><td>1</td></tr></tbody></table>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:group(name#^.name,\"*\"):grid").toString(), is("<table><thead><tr><th></th><th>tk001</th></tr></thead><tbody><tr><th>AR_10000029</th><td>*</td></tr><tr><th>AR_10000060</th><td>*</td></tr><tr><th>AR_10000044</th><td>*</td></tr></tbody></table>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:group(name#^.name,\"*\"):grid").getClass().getSimpleName(), is("HtmlContainer"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:group(name:size#^.name,\"*\"):grid").toString(), is("<table><thead><tr><th></th><th>tk001</th></tr></thead><tbody><tr><th>11</th><td>*\n*\n*</td></tr></tbody></table>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:group(name:size#^.name,\"*\"):grid(:join( ))").toString(), is("<table><thead><tr><th></th><th>tk001</th></tr></thead><tbody><tr><th>11</th><td>* * *</td></tr></tbody></table>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:group(name#\"\",\"*\"):grid").toString(), is("<table><tbody><tr><th>AR_10000029</th><td>*</td></tr><tr><th>AR_10000060</th><td>*</td></tr><tr><th>AR_10000044</th><td>*</td></tr></tbody></table>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:count(name#\"assignments\"):grid()").toString(), is("<table><thead><tr><th></th><th>assignments</th></tr></thead><tbody><tr><th>AR_10000029</th><td>1</td></tr><tr><th>AR_10000060</th><td>1</td></tr><tr><th>AR_10000044</th><td>1</td></tr></tbody></table>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:count(name#\"assignments\"):grid(,totals)").toString(), is("<table><thead><tr><th></th><th>assignments</th></tr></thead><tbody><tr><th>AR_10000029</th><td class=\"numeric\">1</td></tr><tr><th>AR_10000060</th><td class=\"numeric\">1</td></tr><tr><th>AR_10000044</th><td class=\"numeric\">1</td></tr><tr><th>TOTAL</th><td class=\"numeric\">3</td></tr></tbody></table>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:count(name#\"assignments\"):grid").toString(), is("<table><thead><tr><th></th><th>assignments</th></tr></thead><tbody><tr><th>AR_10000029</th><td class=\"numeric\">1</td></tr><tr><th>AR_10000060</th><td class=\"numeric\">1</td></tr><tr><th>AR_10000044</th><td class=\"numeric\">1</td></tr><tr><th>TOTAL</th><td class=\"numeric\">3</td></tr></tbody></table>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:count(name:size#\"assignments\"#name):grid").toString(), is("<table><thead><tr><th></th><th>assignments</th></tr></thead><tbody><tr><th>11</th><td>AR_10000029 = 1\nAR_10000060 = 1\nAR_10000044 = 1</td></tr></tbody></table>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:count(name:size#\"assignments\"#name):grid(:pprint)").toString(), is("<table><thead><tr><th></th><th>assignments</th></tr></thead><tbody><tr><th>11</th><td>AR_10000029 = 1\nAR_10000060 = 1\nAR_10000044 = 1</td></tr></tbody></table>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:count(name:size#\"assignments\"#name):grid(,1)").toString(), is("\n<h2 title=\"11\">11</h2>\n<table><thead><tr><th></th><th>AR_10000044</th><th>AR_10000029</th><th>AR_10000060</th></tr></thead><tbody><tr><th>assignments</th><td>1</td><td>1</td><td>1</td></tr></tbody></table>\n"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:count(name:size#\"assignments\"#name):grid(,1,totals)").toString(), is("\n<h2 title=\"11\">11</h2>\n<table><thead><tr><th></th><th>AR_10000044</th><th>AR_10000029</th><th>AR_10000060</th><th>TOTAL</th></tr></thead><tbody><tr><th>assignments</th><td class=\"numeric\">1</td><td class=\"numeric\">1</td><td class=\"numeric\">1</td><td class=\"numeric\">3</td></tr><tr><th>TOTAL</th><td class=\"numeric\">1</td><td class=\"numeric\">1</td><td class=\"numeric\">1</td><td class=\"numeric\">3</td></tr></tbody></table>\n"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:index(name:size#name,:link(#,${1})):grid").toString(), is("<table><thead><tr><th></th><th>AR_10000044</th><th>AR_10000029</th><th>AR_10000060</th><th>TOTAL</th></tr></thead><tbody><tr><th>11</th><td class=\"numeric\"><span>1</span></td><td class=\"numeric\"><span>1</span></td><td class=\"numeric\"><span>1</span></td><td class=\"numeric\">3</td></tr><tr><th>TOTAL</th><td class=\"numeric\">1</td><td class=\"numeric\">1</td><td class=\"numeric\">1</td><td class=\"numeric\">3</td></tr></tbody></table>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:index(name:size#name,:link(#,${1:barchart})):grid").toString(), is("<table><thead><tr><th></th><th>AR_10000044</th><th>AR_10000029</th><th>AR_10000060</th><th>TOTAL</th></tr></thead><tbody><tr><th>11</th><td class=\"numeric\"><span><span class=\"chart\"><span title=\"1\" style=\"background-color: black; cursor: pointer;\">&hairsp;</span></span></span></td><td class=\"numeric\"><span><span class=\"chart\"><span title=\"1\" style=\"background-color: black; cursor: pointer;\">&hairsp;</span></span></span></td><td class=\"numeric\"><span><span class=\"chart\"><span title=\"1\" style=\"background-color: black; cursor: pointer;\">&hairsp;</span></span></span></td><td class=\"numeric\">3</td></tr><tr><th>TOTAL</th><td class=\"numeric\">1</td><td class=\"numeric\">1</td><td class=\"numeric\">1</td><td class=\"numeric\">3</td></tr></tbody></table>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:index(name:size#name,:link(#,${name:extract([0-9]):count(>):barchart(*=.)})):grid").toString(), is("<table><thead><tr><th></th><th>AR_10000044</th><th>AR_10000029</th><th>AR_10000060</th><th>TOTAL</th></tr></thead><tbody><tr><th>11</th><td class=\"numeric\"><span><span class=\"chart\"><span title=\"0: 5\" style=\"background-color: #cfcd20; cursor: pointer;\">&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;</span><span title=\"1: 1\" style=\"background-color: #c4ca42; cursor: pointer;\">&hairsp;</span><span title=\"4: 2\" style=\"background-color: #a87ff6; cursor: pointer;\">&hairsp;&hairsp;</span></span></span></td><td class=\"numeric\"><span><span class=\"chart\"><span title=\"0: 5\" style=\"background-color: #cfcd20; cursor: pointer;\">&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;</span><span title=\"1: 1\" style=\"background-color: #c4ca42; cursor: pointer;\">&hairsp;</span><span title=\"2: 1\" style=\"background-color: #c81e72; cursor: pointer;\">&hairsp;</span><span title=\"9: 1\" style=\"background-color: #45c48c; cursor: pointer;\">&hairsp;</span></span></span></td><td class=\"numeric\"><span><span class=\"chart\"><span title=\"0: 6\" style=\"background-color: #cfcd20; cursor: pointer;\">&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;</span><span title=\"1: 1\" style=\"background-color: #c4ca42; cursor: pointer;\">&hairsp;</span><span title=\"6: 1\" style=\"background-color: #167909; cursor: pointer;\">&hairsp;</span></span></span></td><td class=\"numeric\">24</td></tr><tr><th>TOTAL</th><td class=\"numeric\">8</td><td class=\"numeric\">8</td><td class=\"numeric\">8</td><td class=\"numeric\">24</td></tr></tbody></table>"));
	}

	@Test
	public void test_chart()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null);
		// Act & Assert
		assertThat("Incorrect HTML", proxy.access("assignedRoles:barchart(:size)").toString(), is("<span class=\"chart\"><span title=\"3\" style=\"background-color: black; cursor: pointer;\">&hairsp;&hairsp;&hairsp;</span></span>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:size:barchart").toString(), is("<span class=\"chart\"><span title=\"3\" style=\"background-color: black; cursor: pointer;\">&hairsp;&hairsp;&hairsp;</span></span>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:size:barchart(5)").toString(), is("<span class=\"chart\"><span title=\"3\" style=\"background-color: black; cursor: pointer;\">&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;</span></span>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:size:barchart(0.5)").toString(), is("<span class=\"chart\"><span title=\"3\" style=\"background-color: black; cursor: pointer;\">&hairsp;&hairsp;</span></span>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:barchart(0.5,:size)").toString(), is("<span class=\"chart\"><span title=\"3\" style=\"background-color: black; cursor: pointer;\">&hairsp;&hairsp;</span></span>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:barchart(:size,2*:size,:size/2)").toString(), is("<span class=\"chart\"><span title=\"3\" style=\"background-color: #698350; cursor: pointer;\">&hairsp;&hairsp;&hairsp;</span><span title=\"6\" style=\"background-color: #47e3ae; cursor: pointer;\">&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;</span><span title=\"1\" style=\"background-color: #e90fcb; cursor: pointer;\">&hairsp;</span></span>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:barchart(#red=:size,#blue=2*:size,#green=:size/2)").toString(), is("<span class=\"chart\"><span title=\"3\" style=\"background-color: red; cursor: pointer;\">&hairsp;&hairsp;&hairsp;</span><span title=\"6\" style=\"background-color: blue; cursor: pointer;\">&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;</span><span title=\"1\" style=\"background-color: green; cursor: pointer;\">&hairsp;</span></span>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:barchart(Normal=:size,Large=2*:size,Small=:size/2)").toString(), is("<span class=\"chart\"><span title=\"Normal: 3\" style=\"background-color: #960b44; cursor: pointer;\">&hairsp;&hairsp;&hairsp;</span><span title=\"Large: 6\" style=\"background-color: #3a69b3; cursor: pointer;\">&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;</span><span title=\"Small: 1\" style=\"background-color: #266006; cursor: pointer;\">&hairsp;</span></span>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:barchart(Normal#red=:size,Large#blue=2*:size,Small#green=:size/2)").toString(), is("<span class=\"chart\"><span title=\"Normal: 3\" style=\"background-color: red; cursor: pointer;\">&hairsp;&hairsp;&hairsp;</span><span title=\"Large: 6\" style=\"background-color: blue; cursor: pointer;\">&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;</span><span title=\"Small: 1\" style=\"background-color: green; cursor: pointer;\">&hairsp;</span></span>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:barchart(Normal#ff0000=:size,Large#0000ff=2*:size,Small#00ff00=:size/2)").toString(), is("<span class=\"chart\"><span title=\"Normal: 3\" style=\"background-color: #ff0000; cursor: pointer;\">&hairsp;&hairsp;&hairsp;</span><span title=\"Large: 6\" style=\"background-color: #0000ff; cursor: pointer;\">&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;</span><span title=\"Small: 1\" style=\"background-color: #00ff00; cursor: pointer;\">&hairsp;</span></span>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:barchart(*=:count(>displayName:match( ?- ?)))").toString(), is("<span class=\"chart\"><span title=\" - : 2\" style=\"background-color: #f574aa; cursor: pointer;\">&hairsp;&hairsp;</span><span title=\"-: 1\" style=\"background-color: #336d5e; cursor: pointer;\">&hairsp;</span></span>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:barchart(*=:count(>displayName:size/30))").toString(), is("<span class=\"chart\"><span title=\"1: 2\" style=\"background-color: #c4ca42; cursor: pointer;\">&hairsp;&hairsp;</span><span title=\"2: 1\" style=\"background-color: #c81e72; cursor: pointer;\">&hairsp;</span></span>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:size:linechart").toString(), is("<span class=\"chart\"><span style=\"background-color: black;\">&thinsp;</span><span>&hairsp;&hairsp;</span><span title=\"3\" style=\"background-color: black; cursor: pointer;\">&thinsp;</span></span>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:size:linechart(2)").toString(), is("<span class=\"chart\"><span style=\"background-color: black;\">&thinsp;</span><span>&hairsp;&hairsp;&hairsp;&hairsp;&hairsp;</span><span title=\"3\" style=\"background-color: black; cursor: pointer;\">&thinsp;</span></span>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:linechart(:size,2*:size,:size/2)").toString(), is("<span class=\"chart\"><span style=\"background-color: black;\">&thinsp;</span><span title=\"1\" style=\"background-color: #e90fcb; cursor: pointer;\">&thinsp;</span><span title=\"3\" style=\"background-color: #698350; cursor: pointer;\">&thinsp;</span><span>&hairsp;</span><span title=\"6\" style=\"background-color: #47e3ae; cursor: pointer;\">&thinsp;</span></span>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:linechart(#red=:size,#blue=2*:size,#green=:size/2)").toString(), is("<span class=\"chart\"><span style=\"background-color: black;\">&thinsp;</span><span title=\"1\" style=\"background-color: green; cursor: pointer;\">&thinsp;</span><span title=\"3\" style=\"background-color: red; cursor: pointer;\">&thinsp;</span><span>&hairsp;</span><span title=\"6\" style=\"background-color: blue; cursor: pointer;\">&thinsp;</span></span>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:linechart(Normal=:size,Large=2*:size,Small=:size/2)").toString(), is("<span class=\"chart\"><span style=\"background-color: black;\">&thinsp;</span><span title=\"Small: 1\" style=\"background-color: #266006; cursor: pointer;\">&thinsp;</span><span title=\"Normal: 3\" style=\"background-color: #960b44; cursor: pointer;\">&thinsp;</span><span>&hairsp;</span><span title=\"Large: 6\" style=\"background-color: #3a69b3; cursor: pointer;\">&thinsp;</span></span>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:linechart(2,Normal=:size,Large=2*:size,Small=:size/2,Limit=3)").toString(), is("<span class=\"chart\"><span style=\"background-color: black;\">&thinsp;</span><span>&hairsp;</span><span title=\"Small: 1\" style=\"background-color: #266006; cursor: pointer;\">&thinsp;</span><span>&hairsp;&hairsp;</span><span title=\"Normal: 3\" style=\"background-color: #960b44; cursor: pointer;\">&thinsp;</span><span title=\"Limit: 3\" style=\"background-color: #80d267; cursor: pointer;\">&thinsp;</span><span>&hairsp;&hairsp;</span><span title=\"Large: 6\" style=\"background-color: #3a69b3; cursor: pointer;\">&thinsp;</span></span>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:linechart(Normal#red=:size,Large#blue=2*:size,Small#green=:size/2)").toString(), is("<span class=\"chart\"><span style=\"background-color: black;\">&thinsp;</span><span title=\"Small: 1\" style=\"background-color: green; cursor: pointer;\">&thinsp;</span><span title=\"Normal: 3\" style=\"background-color: red; cursor: pointer;\">&thinsp;</span><span>&hairsp;</span><span title=\"Large: 6\" style=\"background-color: blue; cursor: pointer;\">&thinsp;</span></span>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:linechart(Normal#ff0000=:size,Large#0000ff=2*:size,Small#00ff00=:size/2)").toString(), is("<span class=\"chart\"><span style=\"background-color: black;\">&thinsp;</span><span title=\"Small: 1\" style=\"background-color: #00ff00; cursor: pointer;\">&thinsp;</span><span title=\"Normal: 3\" style=\"background-color: #ff0000; cursor: pointer;\">&thinsp;</span><span>&hairsp;</span><span title=\"Large: 6\" style=\"background-color: #0000ff; cursor: pointer;\">&thinsp;</span></span>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:linechart(*=:count(>displayName:match( ?- ?)))").toString(), is("<span class=\"chart\"><span style=\"background-color: black;\">&thinsp;</span><span title=\"-: 1\" style=\"background-color: #336d5e; cursor: pointer;\">&thinsp;</span><span title=\" - : 2\" style=\"background-color: #f574aa; cursor: pointer;\">&thinsp;</span></span>"));
		assertThat("Incorrect HTML", proxy.access("assignedRoles:linechart(*=:count(>displayName:size/30))").toString(), is("<span class=\"chart\"><span style=\"background-color: black;\">&thinsp;</span><span title=\"2: 1\" style=\"background-color: #c81e72; cursor: pointer;\">&thinsp;</span><span title=\"1: 2\" style=\"background-color: #c4ca42; cursor: pointer;\">&thinsp;</span></span>"));
	}

	@Test
	public void test_tostring()
	{
		// Arrange
		var parentProxy = OrionQL.wrap(null, null);
		parentProxy.setFormat("${firstname} ${lastname} (${name})");
		var proxy = parentProxy.derive(identity);
		// Act & Assert
		assertThat("Incorrect formatting result", proxy.access("firstname"), is("John"));
		assertThat("Incorrect formatting result", proxy.format("firstname"), is("firstname"));
		assertThat("Incorrect formatting result", proxy.format("${firstname}"), is("John"));
		assertThat("Incorrect formatting result", proxy.toString(), is("John Doe (tk001)"));
	}

	@Test
	public void test_map_join()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null)
				.implant("qotd", Literals.asMap("Monday", "Life is beautiful").add("Tuesday", "Don't worry, be happy"));
		// Act & Assert
		assertThat("Incorrect mapping result", proxy.access("assignedRoles:map(name)"), is(List.of("AR_10000029", "AR_10000044", "AR_10000060")));
		assertThat("Incorrect mapping result", proxy.access("assignedRoles:map(:format(<${name}>))"), is(List.of("<AR_10000029>", "<AR_10000044>", "<AR_10000060>")));
		assertThat("Incorrect mapping result", proxy.access("assignedRoles:map(name:format(<${}>))"), is(List.of("<AR_10000029>", "<AR_10000044>", "<AR_10000060>")));
		assertThat("Incorrect mapping result", proxy.access("assignedRoles:map(name):join").toString(), is("AR_10000029\nAR_10000044\nAR_10000060"));
		assertThat("Incorrect mapping result", proxy.access("assignedRoles:map(name):join(,)").toString(), is("AR_10000029,AR_10000044,AR_10000060"));
		assertThat("Incorrect mapping result", proxy.access("assignedRoles:map(:format(${^.name} -> ${name}))"), is(List.of("tk001 -> AR_10000029" ,"tk001 -> AR_10000044", "tk001 -> AR_10000060")));
		assertThat("Incorrect mapping result", proxy.access("qotd:map(:format(${^.name}: ${key} -> ${value}))"), is(List.of("tk001: Monday -> Life is beautiful" ,"tk001: Tuesday -> Don't worry, be happy")));
		assertThat("Incorrect mapping result", proxy.access("qotd:join(, )").toString(), is("Monday=Life is beautiful, Tuesday=Don't worry, be happy"));
	}

	@Test
	public void test_map_join_implicit_collection()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null);
		// Act & Assert
		assertThat("Incorrect mapping result", proxy.access("firstname:map(@)"), is(List.of("John")));
		assertThat("Incorrect mapping result", proxy.access("firstname:join").toString(), is("John"));
	}

	@Test
	public void test_proxy_chain()
	{
		// Arrange
		Object top = new Identity();
		var parentProxy = OrionQL.wrap(top, null);
		parentProxy.implant("lastname", "Miller");
		parentProxy.setFormat("${firstname} ${lastname} (${name})");
		var childProxy = parentProxy.derive(identity);
		childProxy.implant("lastname", "Smith");
		// Act & Assert
		assertThat("Incorrect formatting", childProxy.toString(), is("John Smith (tk001)"));
		assertThat("Incorrect attribute value", childProxy.access("firstname"), is("John"));
		assertThat("Incorrect implant", childProxy.access("lastname"), is("Smith"));
		assertThat("Incorrect unwrapping", childProxy.unwrap(), is(identity));
		assertThat("Incorrect unwrapping", childProxy.access(""), is(identity));
		assertThat("Incorrect unwrapping", childProxy.access("@"), is(identity));
		assertThat("Incorrect navigation", childProxy.access("^"), is(top));
		assertThat("Incorrect navigation", childProxy.access("^.firstname"), is(nullValue()));
		assertThat("Incorrect navigation", childProxy.access("^.lastname"), is("Miller"));
		assertThat("Incorrect unwrapping", parentProxy.unwrap(), is(top));
		assertThat("Incorrect unwrapping", parentProxy.access(""), is(top));
		assertThat("Incorrect unwrapping", parentProxy.access("@"), is(top));
	}

	@Test
	public void test_null()
	{
		// Arrange
		var proxy = OrionQL.wrap(null, null);
		// Act & Assert
		assertThat("Incorrect attribute value", proxy.access("firstname"), is(nullValue()));
		assertThat("Incorrect formatting result", proxy.access(":format(${firstname} ${lastname})"), is("null null"));
		assertThat("Incorrect formatting result", proxy.access("@:format(${firstname} ${lastname})"), is(nullValue()));
		assertThat("Incorrect formatting result", proxy.format("${firstname} ${lastname}"), is("null null"));
	}

	@Test
	public void test_lookup()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null)
				.setLookupTable("nickname", Literals.asMap("tk001", "Johnny"))
				.setLookupTable("nickName", Literals.asMap(identity, "Johnny"))
				.setLookupTable("slogan", Literals.asMap("Johnny", "Life is beautiful"))
				.setLookupTable("Identity", Literals.asMap(identity.getName(), identity));
		// Act & Assert
		assertThat("Incorrect lookup", proxy.access("name:nickname"), is("Johnny"));
		assertThat("Incorrect lookup", proxy.access(":nickname(${name})"), is("Johnny"));
		assertThat("Incorrect lookup", proxy.access("name:nickname:slogan"), is("Life is beautiful"));
		assertThat("Incorrect lookup", proxy.access(":slogan(${name:nickname})"), is("Life is beautiful"));
		assertThat("Incorrect lookup", proxy.access("name:slogan(${:nickname})"), is("Life is beautiful"));
		assertThat("Incorrect lookup", proxy.access(":slogan(${:nickname(${name})})"), is("Life is beautiful"));
		assertThat("Incorrect lookup", proxy.access(":nickname(${name}):slogan"), is("Life is beautiful"));
		assertThat("Incorrect lookup", proxy.access(":nickName"), is("Johnny"));
		assertThat("Incorrect lookup", proxy.access("lastname:nickname"), is(nullValue()));
		assertThat("Incorrect lookup", proxy.access("name:Identity"), is(identity));
		assertThat("Incorrect lookup", proxy.access(":Identity(${name})"), is(identity));
	}

	@Test
	public void test_db_objects_lookup() throws GeneralException
	{
		// Arrange
		var context = mock(SailPointContext.class);
		when(context.getObject(Identity.class, identity.getName())).thenReturn(identity);
		when(context.getUniqueObject(Identity.class, Filter.eq("name", identity.getName()))).thenReturn(identity);
		when(context.getUniqueObject(Identity.class, Filter.or(Filter.eq("name", identity.getName()), Filter.in("name", List.of(identity.getName()))))).thenReturn(identity);
		when(context.getUniqueObject(Identity.class, Filter.in("name", List.of(identity.getName(), "1) foo, 2) \"bar\"")))).thenReturn(identity);
		when(context.getObjects(Identity.class, new QueryOptions().addFilter(Filter.eq("name", identity.getName())))).thenReturn(List.of(identity));
		when(context.countObjects(Identity.class, new QueryOptions().addFilter(Filter.eq("name", identity.getName())))).thenReturn(1);
		when(context.countObjects(Identity.class, new QueryOptions().addFilter(Filter.or(Filter.eq("id", identity.getName()), Filter.eq("name", identity.getName()))))).thenReturn(1);
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.eq("name", identity.getName())))).thenReturn(List.of(identity).iterator());
		when(context.getObjects(Identity.class, new QueryOptions().addFilter(Filter.or(Filter.eq("id", identity.getName()), Filter.eq("name", identity.getName()))))).thenReturn(List.of(identity));
		var proxy = OrionQL.wrap(identity, context);
		// Act & Assert
		assertThat("Incorrect query result", proxy.access("name:Identity"), is(identity));
		assertThat("Incorrect query result", proxy.access(":Identity(name==$\"name\")"), is(identity));
		assertThat("Incorrect query result", proxy.access(":Identity((name==$\"name\" || name.in({\"tk001\"})))"), is(identity));
		assertThat("Incorrect query result", proxy.access(":Identity(name.in({\"tk001\", \"1) foo, 2) \\\"bar\\\"\"}))"), is(identity));
		assertThat("Incorrect query result", proxy.access(":Identity(name.in({$\"name\", \"1) foo, 2) \\\"bar\\\"\"}))"), is(identity));
		assertThat("Incorrect query result", proxy.access("name:Identity(name==$\"\")"), is(identity));
		assertThat("Incorrect query result", proxy.access("name:Identity(?name==$\"\")"), is(1));
		assertThat("Incorrect query result", proxy.access("name:Identity(?)"), is(1));
		assertThat("Incorrect query result", proxy.access(":Identity([name==$\"name\"])"), is(List.of(identity)));
		assertThat("Incorrect query result", proxy.access("name:Identity([])"), is(List.of(identity)));
		assertThat("Incorrect query result", proxy.access("name:map(:Identity)"), is(List.of(identity)));
		assertThat("Incorrect query result", OrionQL.wrap(identity, context).access("name:map(:Identity)"), is(List.of(identity)));
		assertThat("Incorrect query result", OrionQL.wrap(identity, context).access("name:map(:Identity(?))"), is(List.of(1)));
	}

	@Test
	public void test_db_unconditional_lookup() throws GeneralException
	{
		// Arrange
		var context = mock(SailPointContext.class);
		when(context.search(Identity.class, new QueryOptions(), List.of("firstname", "lastname"))).thenAnswer(invocationOnMock -> Collections.singletonList(new Object[]{"John", "Doe"}).iterator());
		when(context.countObjects(Identity.class, new QueryOptions())).thenReturn(1);
		var proxy = OrionQL.wrap(identity, context);
		// Act & Assert
		assertThat("Incorrect query result", proxy.access(":Identity({firstname,lastname}-)"), equalToObject(Literals.asMap("firstname", "John").add("lastname", "Doe")));
		assertThat("Incorrect query result", proxy.access("name:Identity(?-)"), is(1));
	}

	@Test
	public void test_db_attributes_lookup() throws GeneralException
	{
		// Arrange
		var batchRequest = new BatchRequest();
		batchRequest.setId("caligula");
		var context = mock(SailPointContext.class);
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.eq("name", identity.getName())), List.of("firstname", "lastname"))).thenAnswer(invocationOnMock -> Collections.singletonList(new Object[]{"John", "Doe"}).iterator());
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.eq("name", identity.getName())), List.of("firstname"))).thenAnswer(invocationOnMock -> Collections.singletonList(new Object[]{"John"}).iterator());
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.or(Filter.eq("id", identity.getName()), Filter.eq("name", identity.getName()))), List.of("firstname", "lastname"))).thenReturn(Collections.singletonList(new Object[]{"John", "Doe"}).iterator());
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.or(Filter.eq("id", identity.getName()), Filter.eq("name", identity.getName()))), List.of("firstname"))).thenAnswer(invocationOnMock -> Collections.singletonList(new Object[]{"John"}).iterator());
		when(context.countObjects(BatchRequest.class, new QueryOptions().addFilter(Filter.or(Filter.eq("id", identity.getName()), Filter.eq("name", identity.getName()))))).thenThrow(new GeneralException("could not resolve property: name of: sailpoint.object.BatchRequest"));
		when(context.search(BatchRequest.class, new QueryOptions().addFilter(Filter.eq("id", identity.getName())), List.of("id"))).thenAnswer(invocationOnMock -> Collections.singletonList(new Object[] {batchRequest.getId()}).iterator());
		var proxy = OrionQL.wrap(identity, context);
		// Act & Assert
		assertThat("Incorrect query result", proxy.access(":Identity({firstname,lastname}name==$\"name\")"), equalToObject(Literals.asMap("firstname", "John").add("lastname", "Doe")));
		assertThat("Incorrect query result", proxy.access(":Identity({firstname}name==$\"name\")"), is("John"));
		assertThat("Incorrect query result", proxy.access(":Identity([{firstname,lastname}name==$\"name\"])"), equalToObject(List.of(Literals.asMap("firstname", "John").add("lastname", "Doe"))));
		assertThat("Incorrect query result", proxy.access(":Identity([{firstname}name==$\"name\"])"), is(List.of("John")));
		assertThat("Incorrect query result", proxy.access("name:Identity({firstname,lastname})"), equalToObject(Literals.asMap("firstname", "John").add("lastname", "Doe")));
		assertThat("Incorrect query result", proxy.access("name:Identity({firstname})"), is("John"));
		assertThat("Incorrect query result", proxy.access("name:Identity([{firstname}])"), is(List.of("John")));
		assertThat("Incorrect query result", proxy.access("name:BatchRequest({id})"), is(batchRequest.getId()));
	}

	@Test
	public void test_db_lookup_options() throws GeneralException
	{
		// Arrange
		var context = mock(SailPointContext.class);
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.eq("department", "DEPT")), List.of("name"))).thenReturn(List.of(new Object[]{"tkabc"}, new Object[]{"tk123"}, new Object[]{"tkxyz"}).iterator());
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.eq("department", "DEPT")).setResultLimit(2), List.of("name"))).thenReturn(List.of(new Object[]{"tkabc"}, new Object[]{"tk123"}).iterator());
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.eq("department", "DEPT")).setResultLimit(1), List.of("name"))).thenReturn(Collections.singletonList(new Object[]{"tkabc"}).iterator());
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.eq("department", "DEPT")).addOrdering("name", true), List.of("name"))).thenReturn(List.of(new Object[]{"tk123"}, new Object[]{"tkabc"}, new Object[]{"tkxyz"}).iterator());
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.eq("department", "DEPT")).addOrdering("name", true).setResultLimit(2), List.of("name"))).thenReturn(List.of(new Object[]{"tk123"}, new Object[]{"tkabc"}).iterator());
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.eq("department", "DEPT")).addOrdering("name", true).setResultLimit(1), List.of("name"))).thenReturn(Collections.singletonList(new Object[]{"tk123"}).iterator());
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.eq("department", "DEPT")).addOrdering("name", false), List.of("name"))).thenReturn(List.of(new Object[]{"tkxyz"}, new Object[]{"tkabc"}, new Object[]{"tk123"}).iterator());
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.eq("department", "DEPT")).setResultLimit(1), List.of("name", "lastname"))).thenReturn(Collections.singletonList(new Object[]{"tkabc", "Doe"}).iterator());
		var proxy = OrionQL.wrap(identity, context);
		// Act & Assert
		assertThat("Incorrect query result", proxy.access(":Identity([{name}department==\"DEPT\"])"), is(List.of("tkabc", "tk123", "tkxyz")));
		assertThat("Incorrect query result", proxy.access(":Identity([{name}department==\"DEPT\",2])"), is(List.of("tkabc", "tk123")));
		assertThat("Incorrect query result", proxy.access(":Identity([{name}department==\"DEPT\",1])"), is("tkabc"));
		assertThat("Incorrect query result", proxy.access(":Identity([{name}department==\"DEPT\",name])"), is(List.of("tk123", "tkabc", "tkxyz")));
		assertThat("Incorrect query result", proxy.access(":Identity([{name}department==\"DEPT\",name,2])"), is(List.of("tk123", "tkabc")));
		assertThat("Incorrect query result", proxy.access(":Identity([{name}department==\"DEPT\",name,1])"), is("tk123"));
		assertThat("Incorrect query result", proxy.access(":Identity([{name}department==\"DEPT\",name desc])"), is(List.of("tkxyz", "tkabc", "tk123")));
		assertThat("Incorrect query result", proxy.access(":Identity([{name,lastname}department==\"DEPT\",1])"), equalToObject(Literals.asMap("name", "tkabc").add("lastname", "Doe")));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void test_db_lookup_stream_objects() throws GeneralException
	{
		// Arrange
		var context = mock(SailPointContext.class);
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.eq("department", "DEPT")), "id")).thenAnswer(invocationOnMock -> List.of(new Object[]{"1000"}, new Object[]{"1001"}).iterator());
		when(context.getObjects(Identity.class, new QueryOptions().addFilter(Filter.eq("department", "DEPT")).setResultLimit(1))).thenAnswer(invocationOnMock -> Collections.singletonList(identity));
		when(context.getObjectById(Identity.class, "1000")).thenReturn(identity);
		when(context.getObjectById(Identity.class, "1001")).thenReturn(manager);
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.eq("department", "DEPTX")), "id")).thenReturn(Collections.emptyIterator());
		when(context.getObjects(Identity.class, new QueryOptions().addFilter(Filter.eq("department", "DEPTX")).setResultLimit(1))).thenReturn(Collections.emptyList());
		var proxy = OrionQL.wrap(identity, context);
		// Act & Assert
		assertThat("Incorrect query result", Util.iteratorToList(((Iterable<Object>) proxy.access(":Identity(>department==\"DEPT\")")).iterator()), is(List.of(identity, manager)));
		assertThat("Incorrect query result", Util.iteratorToList(((Iterable<Object>) proxy.access(":Identity(>department==\"DEPT\")")).iterator()), is(List.of(identity, manager)));
		assertThat("Incorrect query result", proxy.access(":Identity(>department==\"DEPT\",1)"), is(identity));
		assertThat("Incorrect query result", proxy.access(":Identity(>department==\"DEPT\",1)"), is(identity));
		assertThat("Incorrect query result", Util.iteratorToList(((Iterable<Object>) proxy.access(":Identity(>department==\"DEPTX\")")).iterator()), is(Collections.emptyList()));
		assertThat("Incorrect query result", proxy.access(":Identity(>department==\"DEPTX\",1)"), is(nullValue()));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void test_db_lookup_stream_attributes() throws GeneralException
	{
		// Arrange
		var context = mock(SailPointContext.class);
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.eq("department", "DEPT")), List.of("name"))).thenAnswer(invocationOnMock -> List.of(new Object[]{"tkabc"}, new Object[]{"tk123"}, new Object[]{"tkxyz"}).iterator());
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.eq("department", "DEPT")).setResultLimit(1), List.of("name"))).thenAnswer(invocationOnMock -> Collections.singletonList(new Object[]{"tkabc"}).iterator());
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.eq("department", "DEPT")), List.of("name", "id"))).thenReturn(List.of(new Object[]{"tkabc", "1000"}, new Object[]{"tk123", "1001"}, new Object[]{"tkxyz", "1002"}).iterator());
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.eq("department", "DEPT")).setResultLimit(1), List.of("name", "id"))).thenReturn(Collections.singletonList(new Object[]{"tkabc", "1000"}).iterator());
		var proxy = OrionQL.wrap(identity, context);
		// Act & Assert
		assertThat("Incorrect query result", Util.iteratorToList(((Iterable<Object>) proxy.access(":Identity(>{name}department==\"DEPT\")")).iterator()), is(List.of("tkabc", "tk123", "tkxyz")));
		assertThat("Incorrect query result", Util.iteratorToList(((Iterable<Object>) proxy.access(":Identity(>{name}department==\"DEPT\")")).iterator()), is(List.of("tkabc", "tk123", "tkxyz")));
		assertThat("Incorrect query result", proxy.access(":Identity(>{name}department==\"DEPT\",1)"), is("tkabc"));
		assertThat("Incorrect query result", proxy.access(":Identity(>{name}department==\"DEPT\",1)"), is("tkabc"));
		assertThat("Incorrect query result", Util.iteratorToList(((Iterable<Object>) proxy.access(":Identity(>{name,id}department==\"DEPT\")")).iterator()), is(List.of(Literals.asMap("name", "tkabc").add("id", "1000"), Literals.asMap("name", "tk123").add("id", "1001"), Literals.asMap("name", "tkxyz").add("id", "1002"))));
		assertThat("Incorrect query result", proxy.access(":Identity(>{name,id}department==\"DEPT\",1)"), equalToObject(Literals.asMap("name", "tkabc").add("id", "1000")));
	}

	@Test
	public void test_db_caching() throws GeneralException
	{
		// Arrange
		var context = mock(SailPointContext.class);
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.eq("name", identity.getName())), List.of("firstname", "lastname"))).thenReturn(Collections.singletonList(new Object[]{"John", "Doe"}).iterator());
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.eq("name", identity.getName())), List.of("firstname"))).thenReturn(Collections.singletonList(new Object[]{"John"}).iterator());
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.or(Filter.eq("id", identity.getName()), Filter.eq("name", identity.getName()))), List.of("firstname", "lastname"))).thenReturn(Collections.singletonList(new Object[]{"John", "Doe"}).iterator());
		when(context.search(Identity.class, new QueryOptions().addFilter(Filter.or(Filter.eq("id", identity.getName()), Filter.eq("name", identity.getName()))), List.of("firstname"))).thenReturn(Collections.singletonList(new Object[]{"John"}).iterator());
		var proxy = OrionQL.wrap(identity, context)
				.setLookupTable("nickname", Literals.asMap("tk001", "Johnny"));
		// Act & Assert
		assertThat("Incorrect lookup", proxy.access("name:nickname"), is("Johnny"));
		assertThat("Incorrect query result", proxy.access(":Identity({firstname,lastname}name==$\"name\")"), equalToObject(Literals.asMap("firstname", "John").add("lastname", "Doe")));
		assertThat("Incorrect query result", proxy.access(":Identity([{firstname}name==$\"name\"])"), is(List.of("John")));
		assertThat("Incorrect query result", proxy.access("name:Identity({firstname,lastname})"), equalToObject(Literals.asMap("firstname", "John").add("lastname", "Doe")));
		assertThat("Incorrect query result", proxy.access("name:Identity([{firstname}])"), is(List.of("John")));
		assertThat("Incorrect caching", proxy.access(":Identity({firstname,lastname}name==$\"name\")"), equalToObject(Literals.asMap("firstname", "John").add("lastname", "Doe")));
		assertThat("Incorrect caching", proxy.access(":Identity([{firstname}name==$\"name\"])"), is(List.of("John")));
		assertThat("Incorrect caching", proxy.access("name:Identity({firstname,lastname})"), equalToObject(Literals.asMap("firstname", "John").add("lastname", "Doe")));
		assertThat("Incorrect caching", proxy.access("name:Identity([{firstname}])"), is(List.of("John")));
		proxy.clearCache();
		assertThat("Incorrect lookup", proxy.access("name:nickname"), is("Johnny"));
		assertThat("Incorrect caching", proxy.access(":Identity({firstname,lastname}name==$\"name\")"), is(nullValue()));
		assertThat("Incorrect caching", proxy.access(":Identity([{firstname}name==$\"name\"])"), is(Collections.emptyList()));
		assertThat("Incorrect caching", proxy.access("name:Identity({firstname,lastname})"), is(nullValue()));
		assertThat("Incorrect caching", proxy.access("name:Identity([{firstname}])"), is(Collections.emptyList()));
	}

	@Test
	public void test_cache()
	{
		// Arrange
		var identityProxy = OrionQL.wrap(identity, null);
		var managerProxy = OrionQL.wrap(manager, null);
		// Act
		identityProxy.access(":cache(${name}.assignedRoles,assignedRoles:map(name))");
		managerProxy.access(":cache(${name}.assignedRoles,assignedRoles:map(name))");
		// Assert
		assertThat("Incorrect caching", identityProxy.access(":cache(foo?)"), is(nullValue()));
		assertThat("Incorrect caching", identityProxy.access(":cache(${name}.assignedRoles)"), is(List.of("AR_10000029", "AR_10000044", "AR_10000060")));
		assertThat("Incorrect caching", managerProxy.access(":cache(${name}.assignedRoles)"), is(nullValue()));
		assertThat("Incorrect caching", identityProxy.access(":cache(${name}.assignedRoles,assignedRoles:map(displayName))"), is(List.of(
				"AR_10000029", "AR_10000044", "AR_10000060")));
		identityProxy.clearCache();
		assertThat("Incorrect caching", identityProxy.access(":cache(${name}.assignedRoles,assignedRoles:map(displayName))"), is(List.of(
				"Archiv Storagesystem Prod - Server Access-Applikationsadmin Archiv (ux)", "FIMS QA - Server Access-Applikationsadmin FIMS (ux)", "fintop-Dev Server Access-Applikationsadmin Fintop (ux)")));
	}

	@Test
	public void test_recache()
	{
		// Arrange
		var proxy = OrionQL.wrap(null, null);
		// Act & Assert
		assertThat("Incorrect caching", proxy.access(":recache(key,:format(John))"), is("John"));
		assertThat("Incorrect caching", proxy.access(":cache(key)"), is("John"));
		assertThat("Incorrect caching", proxy.access(":recache(key,:format(Hans))"), is("Hans"));
		assertThat("Incorrect caching", proxy.access(":cache(key)"), is("Hans"));
	}

	@Test
	public void test_swapcache()
	{
		// Arrange
		var proxy = OrionQL.wrap(null, null);
		// Act & Assert
		assertThat("Incorrect caching", proxy.access(":swapcache(key,1)"), is(nullValue()));
		assertThat("Incorrect caching", proxy.access(":swapcache(key,2)"), is(1));
		assertThat("Incorrect caching", proxy.access(":swapcache(key,3)"), is(2));
		assertThat("Incorrect caching", proxy.access(":cache(key)"), is(3));
	}

	@Test
	public void test_extract()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null)
				.implant("slogan", "Life is beautiful");
		// Act & Assert
		assertThat("Incorrect extract", proxy.access("slogan:match(\\\\b..\\\\b)"), is("is"));
		assertThat("Incorrect extract", proxy.access("slogan:match( (..\\) )"), is("is"));
		assertThat("Incorrect extract", proxy.access("slogan:match((\\\\w+\\) \\\\w+ (\\\\w+\\))"), is(List.of("Life", "beautiful")));
		assertThat("Incorrect extract", proxy.access("slogan:match((?<s>\\\\w+\\) (?<p>\\\\w+\\) (?<o>\\\\w+\\))"), equalToObject(Literals.asMap("s", "Life").add("p", "is").add("o", "beautiful")));
		assertThat("Incorrect extract", proxy.access("inactive:match(.)"), is("f"));
		assertThat("Incorrect extract", proxy.access("slogan:extract([A-Za-z]+)"), is(List.of("Life", "is", "beautiful")));
		assertThat("Incorrect extract", proxy.access("slogan:extract( ([A-Za-z]+\\))"), is(List.of("is", "beautiful")));
		assertThat("Incorrect extract", proxy.access("slogan:extract(\\\\b(\\\\w\\)(.\\))"), is(List.of(List.of("L", "i"), List.of("i", "s"), List.of("b", "e"))));
		assertThat("Incorrect extract", proxy.access("slogan:extract(\\\\b(?<f>\\\\w\\)(?<s>.\\))"), is(List.of(Literals.asMap("f", "L").add("s", "i"), Literals.asMap("f", "i").add("s", "s"), Literals.asMap("f", "b").add("s", "e"))));
		assertThat("Incorrect extract", proxy.access("inactive:extract(.)"), is(List.of("f", "a", "l", "s", "e")));
	}

	@Test
	public void test_replace()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null);
		// Act & Assert
		assertThat("Incorrect replacement", proxy.access("displayName:replace(o,a)"), is("Jahn Dae"));
		assertThat("Incorrect replacement", proxy.access("inactive:replace(a,o)"), is("folse"));
	}

	@Test
	public void test_parent()
	{
		// Arrange
		var parentProxy = OrionQL.wrap(new Identity(), null)
				.implant("quoteOfTheDay", "Life is beautiful");
		var intermediateProxy = parentProxy.derive(identity)
				.implant("slogan", "Don't worry, be happy");
		var proxy = intermediateProxy.derive(identity);
		// Act & Assert
		assertThat("Incorrect value", proxy.access("firstname"), is("John"));
		assertThat("Incorrect parent value", proxy.access("slogan"), is(nullValue()));
		assertThat("Incorrect parent value", proxy.access("quoteOfTheDay"), is(nullValue()));
		assertThat("Incorrect parent value", proxy.access("^.slogan"), is("Don't worry, be happy"));
		assertThat("Incorrect parent value", proxy.access("^.quoteOfTheDay"), is(nullValue()));
		assertThat("Incorrect parent value", proxy.access("^.^.slogan"), is(nullValue()));
		assertThat("Incorrect parent value", proxy.access("^.^.quoteOfTheDay"), is("Life is beautiful"));
	}

	@Test
	public void test_parent_chain()
	{
		// Arrange
		var level0 = OrionQL.wrap(null, null);
		var level1 = level0.derive(new Application());
		var level2 = level1.derive(new ManagedAttribute());
		var level3 = level2.derive(new Identity());
		var level4 = level3.derive(new Identity());
		var level5 = level4.derive(new Bundle());
		var level6 = level5.derive(new Bundle());
		// Act & Assert
		assertThat("Incorrect parent", level6.access("^"), is(level5.unwrap()));
		assertThat("Incorrect parent", level6.access("^Bundle"), is(level5.unwrap()));
		assertThat("Incorrect parent", level6.access("^^Bundle"), is(level5.unwrap()));
		assertThat("Incorrect parent", level6.access("^.^"), is(level4.unwrap()));
		assertThat("Incorrect parent", level6.access("^Identity"), is(level4.unwrap()));
		assertThat("Incorrect parent", level6.access("^.^.^"), is(level3.unwrap()));
		assertThat("Incorrect parent", level6.access("^^Identity"), is(level3.unwrap()));
		assertThat("Incorrect parent", level6.access("^.^.^.^"), is(level2.unwrap()));
		assertThat("Incorrect parent", level6.access("^ManagedAttribute"), is(level2.unwrap()));
		assertThat("Incorrect parent", level6.access("^^ManagedAttribute"), is(level2.unwrap()));
		assertThat("Incorrect parent", level6.access("^.^.^.^.^"), is(level1.unwrap()));
		assertThat("Incorrect parent", level6.access("^Application"), is(level1.unwrap()));
		assertThat("Incorrect parent", level6.access("^^Application"), is(level1.unwrap()));
		assertThat("Incorrect parent", level6.access("^^"), is(level1.unwrap()));
		assertThat("Incorrect parent", level5.access("^Identity"), is(level4.unwrap()));
		assertThat("Incorrect parent", level5.access("^^Identity"), is(level3.unwrap()));
		assertThat("Incorrect parent", level4.access("^Identity"), is(level3.unwrap()));
		assertThat("Incorrect parent", level4.access("^^Identity"), is(level3.unwrap()));
		assertThat("Incorrect parent", level3.access("^ManagedAttribute"), is(level2.unwrap()));
		assertThat("Incorrect parent", level3.access("^^ManagedAttribute"), is(level2.unwrap()));
	}

	@Test
	public void test_this()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null)
				.setLookupTable("nickname", Literals.asMap("tk001", "Johnny"));
		// Act & Assert
		assertThat("Incorrect lookup", proxy.access("name:format(${:nickname|@.firstname})"), is("Johnny"));
		assertThat("Incorrect lookup", proxy.access("lastname:format(${:nickname|@.firstname})"), is("John"));
	}

	@Test
	public void test_union()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null);
		// Act & Assert
		assertThat("Incorrect union", proxy.access(":union(name,firstname,nickname,assignedRoles:map(name))"), is(List.of("tk001", "John", "AR_10000029", "AR_10000044", "AR_10000060")));
	}

	@Test
	public void test_construct()
	{
		// Arrange
		var list = List.of("John", "Doe");
		var map = Literals.asMap("logonid", "tk001").add("startDate", "2024-04-23");
		var proxy = OrionQL.wrap(manager, null)
				.implant("slogan", "Life is beautiful")
				.derive(null)
				.implant("list", list)
				.implant("map", map);
		// Act & Assert
		assertThat("Incorrect decomposition", OrionQL.evaluate(proxy, ":construct(^.slogan,firstname\\,lastname\\,nickname\\,logonid=list,*=map)", null), equalToObject(
				Literals
						.asMap("^.slogan", "Life is beautiful")
						.add("firstname", "John")
						.add("lastname", "Doe")
						.add("nickname", null)
						.add("logonid", "tk001")
						.add("startDate", "2024-04-23")
		));
		assertThat("Incorrect decomposition", OrionQL.evaluateAll(OrionQL.compileAll("^.slogan\nfirstname,lastname,nickname,logonid=list\n*=map", "\n", null, false), proxy), equalToObject(
				Literals
						.asMap("^.slogan", "Life is beautiful")
						.add("firstname", "John")
						.add("lastname", "Doe")
						.add("nickname", null)
						.add("logonid", "tk001")
						.add("startDate", "2024-04-23")
		));
		assertThat("Incorrect decomposition", OrionQL.evaluate(list, ":construct(firstname\\,lastname=)", null), equalToObject(
				Literals
						.asMap("firstname", "John")
						.add("lastname", "Doe")
		));
		assertThat("Incorrect decomposition", OrionQL.evaluate(map, ":construct(year=startDate:match(....),*=)", null), equalToObject(
				Literals
						.asMap("year", "2024")
						.add("logonid", "tk001")
						.add("startDate", "2024-04-23")
		));
	}

	@Test
	public void test_flatten()
	{
		// Arrange
		//@formatter:off
		var proxy = OrionQL.wrap(
				Literals.asMap(
						"name", (Object) "trentheart").add(
						"that", Literals.asMap(
								"name", (Object) "butorsmile").add(
								"list", List.of(
										Literals.asMap("name", (Object) "worldmakey"),
										Literals.asMap(
												"name", (Object) "smilingfee").add(
												"elements", List.of(
														Literals.asMap(
																"name", (Object) "upsheevery").add(
																"children", List.of(
																		"whennevers",
																		"thathurtwi"
																)
														)
												)
										),
										Literals.asMap(
												"name", (Object) "thercancon").add(
												"elements", List.of(
														Literals.asMap(
																"name", (Object) "youthediff").add(
																"children", "heartinval"
														)
												)
										),
										Literals.asMap(
												"name", (Object) "toyouryoup").add(
												"elements", List.of(
														Literals.asMap("name", (Object) "butyourkee")
												)
										)
								)
						)).add(
						"other", Literals.asMap(
								"name", (Object) "worryatthe").add(
								"list", List.of(
										Literals.asMap(
												"name", (Object) "thatandfin").add(
												"entries", Literals.asMap(
														"humanythin", (Object) "andyouhigh").add(
														"wheneveran", List.of(
																"thebelingt",
																"oncegooden"
														)
												)
										)
								)
						)
				),
				null
		);
		//@formatter:on
		// Act & Assert
		assertThat("Incorrect result",
				   proxy.access("that:flatten(list#elements#children):map(:format(${^.^.^.^.name}:${^.^.^.name}:${^.^.name}:${^.name}:${@}))"),
				   is(List.of(
						   "trentheart:butorsmile:smilingfee:upsheevery:whennevers",
						   "trentheart:butorsmile:smilingfee:upsheevery:thathurtwi",
						   "trentheart:butorsmile:thercancon:youthediff:heartinval"
				   ))
		);
		assertThat("Incorrect result",
				   proxy.access(":flatten(that.list#elements#children):map(:format(${^.^.^.name}:${^.^.name}:${^.name}:${@}))"),
				   is(List.of(
						   "trentheart:smilingfee:upsheevery:whennevers",
						   "trentheart:smilingfee:upsheevery:thathurtwi",
						   "trentheart:thercancon:youthediff:heartinval"
				   ))
		);
		assertThat("Incorrect result",
				   proxy.access(":flatten(this.list#elements#children):map(:format(${^.^.^.name}:${^.^.name}:${^.name}:${@}))"),
				   is(List.of())
		);
		assertThat("Incorrect result",
				   proxy.access(":flatten(other.list#entries#value):map(:format(${^.^.^.name}:${^.^.name}:${^.key}:${@}))"),
				   is(List.of(
						   "trentheart:thatandfin:humanythin:andyouhigh",
						   "trentheart:thatandfin:wheneveran:thebelingt",
						   "trentheart:thatandfin:wheneveran:oncegooden"
				   ))
		);
	}

	@Test
	public void test_recursive_flatten()
	{
		// Arrange
		var proxy = OrionQL.wrap(subordinate, null);
		// Act & Assert
		assertThat("Incorrect walk", proxy.access(":flatten(manager+):map(firstname):join").toString(), is("John\nHans"));
		assertThat("Incorrect walk", proxy.access(":flatten(manager+,2):map(firstname):join").toString(), is("John\nHans"));
		assertThat("Incorrect walk", proxy.access(":flatten(manager*):map(firstname):join").toString(), is("Calpurnius\nJohn\nHans"));
		assertThat("Incorrect walk", proxy.access(":flatten(manager*,1):map(firstname):join").toString(), is("Calpurnius\nJohn"));
		assertThat("Incorrect walk", proxy.access(":flatten(manager*,eq(\"firstname\", \"John\")):map(firstname):join").toString(), is("Calpurnius\nJohn"));
		assertThat("Incorrect walk", proxy.access(":flatten(manager*,2):map(firstname):join").toString(), is("Calpurnius\nJohn\nHans"));
		assertThat("Incorrect walk", proxy.access(":flatten(manager*):map(:format(${firstname}: ${:flatten(^?+):map(firstname):join(,)})):join").toString(), is("Calpurnius: \nJohn: Calpurnius\nHans: John,Calpurnius"));
		assertThat("Incorrect walk", proxy.access(":flatten(manager*):map(:format(${firstname}: ${:flatten(^?*):map(firstname):join(,)})):join").toString(), is("Calpurnius: Calpurnius\nJohn: John,Calpurnius\nHans: Hans,John,Calpurnius"));
		assertThat("Incorrect walk", proxy.access(":flatten(manager*#assignedRoles):map(:format(${^.firstname}:${name})):join").toString(), is("Calpurnius:AR_10000029\nCalpurnius:AR_10000060\nJohn:AR_10000029\nJohn:AR_10000044\nJohn:AR_10000060"));
		assertThat("Incorrect walk", proxy.access("manager:flatten(owner|assignedRoles*):map(name):join").toString(), is("tk001\nAR_10000029\nAR_10000044\nAR_10000060\ntk000"));
	}

	@Test
	public void test_flatten_output_processing()
	{
		// Arrange
		var proxy = OrionQL.wrap(subordinate, null);
		// Act & Assert
		assertThat("Incorrect sort", proxy.access(":flatten(manager*#assignedRoles):sort(name):map(:format(${^.firstname}:${name})):join").toString(), is("Calpurnius:AR_10000029\nJohn:AR_10000029\nJohn:AR_10000044\nCalpurnius:AR_10000060\nJohn:AR_10000060"));
		assertThat("Incorrect select", proxy.access(":flatten(manager*#assignedRoles):select(eq(\"name\", \"AR_10000029\")):map(:format(${^.firstname}:${name})):join").toString(), is("Calpurnius:AR_10000029\nJohn:AR_10000029"));
		assertThat("Incorrect select", proxy.access(":flatten(manager*#assignedRoles):select(1,eq(\"name\", \"AR_10000029\")):format(${^.firstname}:${name})"), is("Calpurnius:AR_10000029"));
	}

	@Test
	public void test_flatten_parent_references()
	{
		// Arrange
		var proxy = OrionQL.wrap("abcdef", null);
		// Act & Assert
		assertThat("Incorrect parent chain", proxy.access(":flatten(:extract(..)#:extract(.)):map(:inspect( )):join( | )").toString(), is("a ^ ab ^ abcdef | b ^ ab ^ abcdef | c ^ cd ^ abcdef | d ^ cd ^ abcdef | e ^ ef ^ abcdef | f ^ ef ^ abcdef"));
		assertThat("Incorrect parent chain", proxy.access(":extract(....):flatten(#:extract(..)#:extract(.)):map(:inspect( )):join( | )").toString(), is("a ^ ab ^ abcd ^ [abcd] ^ abcdef | b ^ ab ^ abcd ^ [abcd] ^ abcdef | c ^ cd ^ abcd ^ [abcd] ^ abcdef | d ^ cd ^ abcd ^ [abcd] ^ abcdef"));
		assertThat("Incorrect parent chain", proxy.access(":flatten(:extract(..)#:extract(.)):select(2):map(:inspect( )):join( | )").toString(), is("a ^ ab ^ abcdef | b ^ ab ^ abcdef"));
		assertThat("Incorrect parent chain", proxy.access(":flatten(:extract(..)#:extract(.)):select(1):inspect( )"), is("a @ abcdef ^ ab ^ abcdef"));
		assertThat("Incorrect parent chain", proxy.access(":match(....):flatten(:extract(..)#:extract(.)):map(:inspect( )):join( | )").toString(), is("a ^ ab ^ abcd ^ abcdef | b ^ ab ^ abcd ^ abcdef | c ^ cd ^ abcd ^ abcdef | d ^ cd ^ abcd ^ abcdef"));
		assertThat("Incorrect parent chain", proxy.access(":match(....):flatten(:extract(..)#:extract(.)):select(2):map(:inspect( )):join( | )").toString(), is("a ^ ab ^ abcd ^ abcdef | b ^ ab ^ abcd ^ abcdef"));
		assertThat("Incorrect parent chain", proxy.access(":match(....):flatten(:extract(..)#:extract(.)):select(1):inspect( )"), is("a @ abcdef ^ ab ^ abcd ^ abcdef"));
		assertThat("Incorrect parent chain", proxy.access(":flatten(:extract(...)#^:extract(..)):map(:inspect( )):join( | )").toString(), is("ab ^ abc ^ abcdef | cd ^ abc ^ abcdef | ef ^ abc ^ abcdef | ab ^ def ^ abcdef | cd ^ def ^ abcdef | ef ^ def ^ abcdef"));
		assertThat("Incorrect parent chain", proxy.access(":match(....):flatten(:extract(..)#:extract(.)#^):map(:inspect( )):join( | )").toString(), is("ab ^ abcd ^ abcdef | ab ^ abcd ^ abcdef | cd ^ abcd ^ abcdef | cd ^ abcd ^ abcdef"));
		assertThat("Incorrect parent chain", proxy.access(":match(....):flatten(:extract(..)#:extract(.)#^:format(${})):map(:inspect( )):join( | )").toString(), is("ab ^ a ^ ab ^ abcd ^ abcdef | ab ^ b ^ ab ^ abcd ^ abcdef | cd ^ c ^ cd ^ abcd ^ abcdef | cd ^ d ^ cd ^ abcd ^ abcdef"));
		assertThat("Incorrect parent chain", proxy.access(":match(....):flatten(:extract(..)#:extract(.)#^.@@):map(:inspect( )):join( | )").toString(), is("ab ^ a ^ ab ^ abcd ^ abcdef | ab ^ b ^ ab ^ abcd ^ abcdef | cd ^ c ^ cd ^ abcd ^ abcdef | cd ^ d ^ cd ^ abcd ^ abcdef"));
		assertThat("Incorrect parent chain", proxy.access(":match(....):flatten(:extract(..)#@@):map(:inspect( )):join( | )").toString(), is("ab ^ ab ^ abcd ^ abcdef | cd ^ cd ^ abcd ^ abcdef"));
		assertThat("Incorrect parent chain", proxy.access(":match(....):flatten(:extract(..)#^:extract(..)):map(:inspect( )):join( | )").toString(), is("ab ^ ab ^ abcd ^ abcdef | cd ^ ab ^ abcd ^ abcdef | ab ^ cd ^ abcd ^ abcdef | cd ^ cd ^ abcd ^ abcdef"));
		assertThat("Incorrect parent chain", proxy.access(":match(....):flatten(:extract(..)#^.^:extract(...)):map(:inspect( )):join( | )").toString(), is("abc ^ ab ^ abcd ^ abcdef | def ^ ab ^ abcd ^ abcdef | abc ^ cd ^ abcd ^ abcdef | def ^ cd ^ abcd ^ abcdef"));
		assertThat("Incorrect parent chain", proxy.access(":flatten(:extract(..(.+\\))*):map(:inspect( )):join( | )").toString(), is("abcdef | cdef ^ abcdef | ef ^ cdef ^ abcdef"));
		assertThat("Incorrect parent chain", proxy.access(":flatten(:extract(...)#:extract(.(.+\\))*):map(:inspect( )):join( | )").toString(), is("abc ^ abcdef | bc ^ abc ^ abcdef | c ^ bc ^ abc ^ abcdef | def ^ abcdef | ef ^ def ^ abcdef | f ^ ef ^ def ^ abcdef"));
		assertThat("Incorrect parent chain", proxy.access(":flatten(:extract(..(.+\\))*#:extract(.)):map(:inspect( )):join( | )").toString(), is("a ^ abcdef | b ^ abcdef | c ^ abcdef | d ^ abcdef | e ^ abcdef | f ^ abcdef | c ^ cdef ^ abcdef | d ^ cdef ^ abcdef | e ^ cdef ^ abcdef | f ^ cdef ^ abcdef | e ^ ef ^ cdef ^ abcdef | f ^ ef ^ cdef ^ abcdef"));
		assertThat("Incorrect parent chain", proxy.access(":flatten(:extract(...)#:extract(.(.+\\))*#:extract(.)):map(:inspect( )):join( | )").toString(), is("a ^ abc ^ abcdef | b ^ abc ^ abcdef | c ^ abc ^ abcdef | b ^ bc ^ abc ^ abcdef | c ^ bc ^ abc ^ abcdef | c ^ c ^ bc ^ abc ^ abcdef | d ^ def ^ abcdef | e ^ def ^ abcdef | f ^ def ^ abcdef | e ^ ef ^ def ^ abcdef | f ^ ef ^ def ^ abcdef | f ^ f ^ ef ^ def ^ abcdef"));
		assertThat("Incorrect parent chain", proxy.access(":match(.....):flatten(:extract(...)#:extract(.(.+\\))*#:extract(.)):map(:inspect( )):join( | )").toString(), is("a ^ abc ^ abcde ^ abcdef | b ^ abc ^ abcde ^ abcdef | c ^ abc ^ abcde ^ abcdef | b ^ bc ^ abc ^ abcde ^ abcdef | c ^ bc ^ abc ^ abcde ^ abcdef | c ^ c ^ bc ^ abc ^ abcde ^ abcdef"));
	}

	@Test
	public void test_simple_conversions()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null);
		// Act & Assert
		assertThat("Incorrect conversion", proxy.access("attributes:pprint"), is("firstname = John\norgUnit = CIT-DOE\nlastname = Doe\norgUnit.id = #CIT-DOE"));
		assertThat("Incorrect conversion", proxy.access("attributes:pprint(true)"), is("{\n    firstname = John\n    orgUnit = CIT-DOE\n    lastname = Doe\n    orgUnit.id = #CIT-DOE\n}"));
		assertThat("Incorrect conversion", proxy.access(":toXml"), is("<Identity created=\"1653985801000\" name=\"tk001\">\n  <Attributes>\n    <Map>\n      <entry key=\"firstname\" value=\"John\"/>\n      <entry key=\"lastname\" value=\"Doe\"/>\n      <entry key=\"orgUnit\" value=\"CIT-DOE\"/>\n      <entry key=\"orgUnit.id\" value=\"#CIT-DOE\"/>\n    </Map>\n  </Attributes>\n  <AssignedRoles>\n    <Reference class=\"sailpoint.object.Bundle\" name=\"AR_10000029\"/>\n    <Reference class=\"sailpoint.object.Bundle\" name=\"AR_10000044\"/>\n    <Reference class=\"sailpoint.object.Bundle\" name=\"AR_10000060\"/>\n  </AssignedRoles>\n  <Manager>\n    <Reference class=\"sailpoint.object.Identity\" name=\"tk000\"/>\n  </Manager>\n</Identity>\n"));
		assertThat("Incorrect conversion", proxy.access("firstname:toXml"), is("<String>John</String>\n"));
		assertThat("Incorrect conversion", proxy.access("attributes:toXml"), is("<Attributes>\n  <Map>\n    <entry key=\"firstname\" value=\"John\"/>\n    <entry key=\"lastname\" value=\"Doe\"/>\n    <entry key=\"orgUnit\" value=\"CIT-DOE\"/>\n    <entry key=\"orgUnit.id\" value=\"#CIT-DOE\"/>\n  </Map>\n</Attributes>\n"));
		assertThat("Incorrect conversion", proxy.access("assignedRoles:toXml"), is("<List>\n  <Bundle displayName=\"Archiv Storagesystem Prod - Server Access-Applikationsadmin Archiv (ux)\" name=\"AR_10000029\">\n    <Attributes>\n      <Map>\n        <entry key=\"application.name\" value=\"Archiv\"/>\n        <entry key=\"sysDescriptions\">\n          <value>\n            <Map>\n              <entry key=\"en_US\" value=\"Archiv Storagesystem Prod - Server Access-Applikationsadmin Archiv (ux) (AR_10000029)\"/>\n            </Map>\n          </value>\n        </entry>\n      </Map>\n    </Attributes>\n  </Bundle>\n  <Bundle displayName=\"FIMS QA - Server Access-Applikationsadmin FIMS (ux)\" name=\"AR_10000044\">\n    <Attributes>\n      <Map>\n        <entry key=\"application.name\" value=\"FIMS\"/>\n        <entry key=\"sysDescriptions\">\n          <value>\n            <Map>\n              <entry key=\"en_US\" value=\"FIMS QA - Server Access-Applikationsadmin FIMS (ux) (AR_10000044)\"/>\n            </Map>\n          </value>\n        </entry>\n      </Map>\n    </Attributes>\n  </Bundle>\n  <Bundle displayName=\"fintop-Dev Server Access-Applikationsadmin Fintop (ux)\" name=\"AR_10000060\">\n    <Attributes>\n      <Map>\n        <entry key=\"application.name\" value=\"Fintop\"/>\n        <entry key=\"sysDescriptions\">\n          <value>\n            <Map>\n              <entry key=\"en_US\" value=\"fintop-Dev Server Access-Applikationsadmin Fintop (ux) (AR_10000060)\"/>\n            </Map>\n          </value>\n        </entry>\n      </Map>\n    </Attributes>\n    <Owner>\n      <Reference class=\"sailpoint.object.Identity\" name=\"tk000\"/>\n    </Owner>\n  </Bundle>\n</List>\n"));
	}

	@Test
	public void test_parse_xml() throws GeneralException
	{
		// Arrange
		var context = mock(SailPointContext.class);
		when(context.getReferencedObject("sailpoint.object.Identity", null, manager.getName())).thenReturn(manager);
		when(context.getReferencedObject("sailpoint.object.Bundle", null, bundle_AR_10000029.getName())).thenReturn(bundle_AR_10000029);
		when(context.getReferencedObject("sailpoint.object.Bundle", null, bundle_AR_10000044.getName())).thenReturn(bundle_AR_10000044);
		when(context.getReferencedObject("sailpoint.object.Bundle", null, bundle_AR_10000060.getName())).thenReturn(bundle_AR_10000060);
		var proxy = OrionQL.wrap("<Identity created=\"1653985801000\" name=\"tk001\">\n  <Attributes>\n    <Map>\n      <entry key=\"firstname\" value=\"John\"/>\n      <entry key=\"lastname\" value=\"Doe\"/>\n      <entry key=\"orgUnit\" value=\"CIT-DOE\"/>\n      <entry key=\"orgUnit.id\" value=\"#CIT-DOE\"/>\n    </Map>\n  </Attributes>\n  <AssignedRoles>\n    <Reference class=\"sailpoint.object.Bundle\" name=\"AR_10000029\"/>\n    <Reference class=\"sailpoint.object.Bundle\" name=\"AR_10000044\"/>\n    <Reference class=\"sailpoint.object.Bundle\" name=\"AR_10000060\"/>\n  </AssignedRoles>\n  <Manager>\n    <Reference class=\"sailpoint.object.Identity\" name=\"tk000\"/>\n  </Manager>\n</Identity>\n", context);
		// Act & Assert
		assertThat("Incorrect conversion", proxy.access(":parseXml:toXml"), is("<Identity created=\"1653985801000\" name=\"tk001\">\n  <Attributes>\n    <Map>\n      <entry key=\"firstname\" value=\"John\"/>\n      <entry key=\"lastname\" value=\"Doe\"/>\n      <entry key=\"orgUnit\" value=\"CIT-DOE\"/>\n      <entry key=\"orgUnit.id\" value=\"#CIT-DOE\"/>\n    </Map>\n  </Attributes>\n  <AssignedRoles>\n    <Reference class=\"sailpoint.object.Bundle\" name=\"AR_10000029\"/>\n    <Reference class=\"sailpoint.object.Bundle\" name=\"AR_10000044\"/>\n    <Reference class=\"sailpoint.object.Bundle\" name=\"AR_10000060\"/>\n  </AssignedRoles>\n  <Manager>\n    <Reference class=\"sailpoint.object.Identity\" name=\"tk000\"/>\n  </Manager>\n</Identity>\n"));
	}

	@Test
	public void test_operators()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null);
		proxy.implant("greeted", Literals.asDate("2022-05-31 10:31:00"));
		proxy.implant("enum", ProvisioningPlan.Operation.Add);
		// Act & Assert
		assertThat("Incorrect result", proxy.access("assignedRoles:size+5"), is(8));
		assertThat("Incorrect result", proxy.access("assignedRoles:size+5."), is(8.));
		assertThat("Incorrect result", proxy.access("5.+assignedRoles:size"), is(8.));
		assertThat("Incorrect result", proxy.access("5.+assignedRoles:size+1."), is(9.));
		assertThat("Incorrect result", proxy.access("assignedRoles:size-5"), is(-2));
		assertThat("Incorrect result", proxy.access("assignedRoles:size-5."), is(-2.));
		assertThat("Incorrect result", proxy.access("5.-assignedRoles:size"), is(2.));
		assertThat("Incorrect result", proxy.access("5.-assignedRoles:size-1."), is(1.));
		assertThat("Incorrect result", proxy.access("assignedRoles:size*5"), is(15));
		assertThat("Incorrect result", proxy.access("assignedRoles:size*5."), is(15.));
		assertThat("Incorrect result", proxy.access("5.*assignedRoles:size"), is(15.));
		assertThat("Incorrect result", proxy.access("5.*assignedRoles:size*1."), is(15.));
		assertThat("Incorrect result", proxy.access("assignedRoles:size*\"5\""), is(15));
		assertThat("Incorrect result", proxy.access("assignedRoles:size/2"), is(1));
		assertThat("Incorrect result", proxy.access("assignedRoles:size/2."), is(1.5));
		assertThat("Incorrect result", proxy.access("(assignedRoles:size+1)/2"), is(2));
		assertThat("Incorrect result", proxy.access("6./assignedRoles:size"), is(2.));
		assertThat("Incorrect result", proxy.access("6./assignedRoles:size/2."), is(1.));
		assertThat("Incorrect result", proxy.access("greeted-created"), is(59));
		assertThat("Incorrect result", proxy.access("(created+100)-created"), is(100));
		assertThat("Incorrect result", proxy.access("(created+10000L)-created"), is(10));
		assertThat("Incorrect result", proxy.access("created-(created-100)"), is(100));
		assertThat("Incorrect result", proxy.access("(created+100)-created"), is(100));
		assertThat("Incorrect result", proxy.access("(created+10000L)-created"), is(10));
		assertThat("Incorrect result", proxy.access("(:date(2046-09-29)-:date(1984-03-29))/86400"), is(22829));
		assertThat("Incorrect result", proxy.access("assignedRoles:size*\"\\20BF\""), is("₿₿₿"));
		assertThat("Incorrect result", proxy.access("assignedRoles:size*\"\\20bf\""), is("₿₿₿"));
		assertThat("Incorrect result", proxy.access("assignedRoles:size/2.*\"\\20bf\""), is("₿"));
		assertThat("Incorrect result", proxy.access("assignedRoles:size==3"), is(true));
		assertThat("Incorrect result", proxy.access("assignedRoles:size!=3"), is(false));
		assertThat("Incorrect result", proxy.access("assignedRoles:size-3==0"), is(true));
		assertThat("Incorrect result", proxy.access("assignedRoles:size>2"), is(true));
		assertThat("Incorrect result", proxy.access("assignedRoles:size>=3"), is(true));
		assertThat("Incorrect result", proxy.access("assignedRoles:size<4"), is(true));
		assertThat("Incorrect result", proxy.access("assignedRoles:size<=3"), is(true));
		assertThat("Incorrect result", proxy.access("assignedRoles:size>3"), is(false));
		assertThat("Incorrect result", proxy.access("assignedRoles:size>=4"), is(false));
		assertThat("Incorrect result", proxy.access("assignedRoles:size<3"), is(false));
		assertThat("Incorrect result", proxy.access("assignedRoles:size<=2"), is(false));
		assertThat("Incorrect result", proxy.access("(greets|assignedRoles):size==3"), is(true));
		assertThat("Incorrect result", proxy.access("(assignedRoles:size|2)==3"), is(true));
		assertThat("Incorrect result", proxy.access("(2|assignedRoles:size)==2"), is(true));
		assertThat("Incorrect result", proxy.access("(assignedRoles:size-1|3)==2"), is(true));
		assertThat("Incorrect result", proxy.access("assignedRoles:size-1|3==2"), is(2));
		assertThat("Incorrect result", proxy.access("assignedRoles:size==3==true"), is(true));
		assertThat("Incorrect result", proxy.access("enum==\"Add\""), is(true));
	}

	@Test
	public void test_aggregation()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null);
		proxy.implant("list", List.of(1, 2, 3, 4, 5));
		// Act & Assert
		assertThat("Incorrect result", proxy.access("list:sum"), is(15));
		assertThat("Incorrect result", proxy.access("list:avg"), is(3.));
		assertThat("Incorrect result", proxy.access("list:max"), is(5));
		assertThat("Incorrect result", proxy.access("list:min"), is(1));
		assertThat("Incorrect result", proxy.access("assignedRoles:sum(displayName:size)"), is(176));
		assertThat("Incorrect result", proxy.access("assignedRoles:avg(displayName:size)"), is(176/3.));
		assertThat("Incorrect result", proxy.access("assignedRoles:max(displayName:size)"), is(71));
		assertThat("Incorrect result", proxy.access("assignedRoles:min(displayName:size)"), is(51));
	}

	@Test
	public void test_date()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null);
		// Act & Assert
		assertThat("Incorrect date", ((Date) proxy.access(":now")).getTime() - System.currentTimeMillis(), greaterThan(-1000L));
		assertThat("Incorrect date", proxy.access(":now(-0d):format(${%yyyy-MM-dd})"), is(String.format("%tF", new Date())));
		assertThat("Incorrect date", proxy.access(":date(2022)"), is(Literals.asDate("2022-01-01 00:00:00")));
		assertThat("Incorrect date", proxy.access(":date(2022-10)"), is(Literals.asDate("2022-10-01 00:00:00")));
		assertThat("Incorrect date", proxy.access(":date(2022-10-31)"), is(Literals.asDate("2022-10-31 00:00:00")));
		assertThat("Incorrect date", proxy.access(":date(2022-10-31T09)"), is(Literals.asDate("2022-10-31 09:00:00")));
		assertThat("Incorrect date", proxy.access(":date(2022-10-31T09:30)"), is(Literals.asDate("2022-10-31 09:30:00")));
		assertThat("Incorrect date", proxy.access(":date(2022-10-31T09:30:21)"), is(Literals.asDate("2022-10-31 09:30:21")));
		assertThat("Incorrect date", proxy.access("\"2022-10-31\":date(yyyy-MM-dd)"), is(Literals.asDate("2022-10-31 00:00:00")));
		assertThat("Incorrect date", proxy.access("\"1653899401\":date(Ts)"), is(Literals.asDate("2022-05-30 10:30:01")));
		assertThat("Incorrect date", proxy.access("\"1653899401000\":date(TQ)"), is(Literals.asDate("2022-05-30 10:30:01")));
	}

	@Test
	public void test_date_offset()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null);
		// for reference: identity.created == 2022-05-31 10:30:01
		// Act & Assert
		assertThat("Incorrect date", proxy.access("created-10s"), is(Literals.asDate("2022-05-31 10:29:51")));
		assertThat("Incorrect date", proxy.access("created+10s"), is(Literals.asDate("2022-05-31 10:30:11")));
		assertThat("Incorrect date", proxy.access("created-1m"), is(Literals.asDate("2022-05-31 10:29:01")));
		assertThat("Incorrect date", proxy.access("created-1h"), is(Literals.asDate("2022-05-31 09:30:01")));
		assertThat("Incorrect date", proxy.access("created-1d"), is(Literals.asDate("2022-05-30 10:30:01")));
		assertThat("Incorrect date", proxy.access("created-1d2h10m3s"), is(Literals.asDate("2022-05-30 08:19:58")));
		assertThat("Incorrect date", proxy.access("created-1d2h+10m3s"), is(Literals.asDate("2022-05-30 08:40:04")));
		assertThat("Incorrect date", proxy.access("created:date(-10s)"), is(Literals.asDate("2022-05-31 10:29:51")));
		assertThat("Incorrect date", proxy.access("created:date(+10s)"), is(Literals.asDate("2022-05-31 10:30:11")));
		assertThat("Incorrect date", proxy.access("created:date(-0m)"), is(Literals.asDate("2022-05-31 10:30:00")));
		assertThat("Incorrect date", proxy.access("created:date(-0h)"), is(Literals.asDate("2022-05-31 10:00:00")));
		assertThat("Incorrect date", proxy.access("created:date(-0d)"), is(Literals.asDate("2022-05-31 00:00:00")));
		assertThat("Incorrect date", proxy.access("created:date(-0w)"), is(Literals.asDate("2022-05-29 00:00:00")));
		assertThat("Incorrect date", proxy.access("created:date(-0W)"), is(Literals.asDate("2022-05-30 00:00:00")));
		assertThat("Incorrect date", proxy.access("created:date(-0M)"), is(Literals.asDate("2022-05-01 00:00:00")));
		assertThat("Incorrect date", proxy.access("created:date(-0Y)"), is(Literals.asDate("2022-01-01 00:00:00")));
		// DST switch
		assertThat("Incorrect date", proxy.access(":date(2022-03-28):date(-24h)"), is(Literals.asDate("2022-03-26 23:00:00")));
		assertThat("Incorrect date", proxy.access(":date(2022-03-28):date(-1d)"), is(Literals.asDate("2022-03-27 00:00:00")));
		// now ignores input (this test might fail if launched immediately before midnight)
		assertThat("Incorrect date", proxy.access("created:now(-0d):format(${%yyyy-MM-dd})"), is(new SimpleDateFormat("yyyy-MM-dd").format(new Date())));
		assertThat("Incorrect date", proxy.access("created:date(-0d):format(${%yyyy-MM-dd})"), is("2022-05-31"));
		// AD timestamp conversion
		assertThat("Incorrect date", proxy.access(":format(${\"133863188756201483\":replace(....$,):date(TQ)-134774d1h})"), is("2025-03-13 05:54:35"));
	}

	@Test
	public void test_indexing()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null);
		// Act & Assert
		assertThat("Incorrect aggregation result", proxy.access("assignedRoles:count(>:format(${owner.name}))"), equalToObject(Literals.asMap("null", 2).add("tk000", 1)));
		assertThat("Incorrect aggregation result", proxy.access("assignedRoles:group(>:format(${owner.name}),name)"), equalToObject(Literals.asMap("null", List.of("AR_10000029", "AR_10000044")).add("tk000", List.of("AR_10000060"))));
		assertThat("Incorrect aggregation result", proxy.access("assignedRoles:index(>:format(${owner.name})#name,name)"), equalToObject(Literals.asMap("null", Literals.asMap("AR_10000029", "AR_10000029").add("AR_10000044", "AR_10000044")).add("tk000", Literals.asMap("AR_10000060", "AR_10000060"))));
	}

	@Test
	public void test_indexing_parameterless()
	{
		// Arrange
		var proxy = OrionQL.wrap("a", null);
		// Act & Assert
		assertThat("Incorrect aggregation result", proxy.access(":count"), equalToObject(Literals.asMap("a", 1)));
		assertThat("Incorrect aggregation result", proxy.access(":group"), equalToObject(Literals.asMap("a", List.of(proxy))));
		assertThat("Incorrect aggregation result", proxy.access(":index"), equalToObject(Literals.asMap("a", proxy)));
	}

	@Test
	public void test_indexaccess()
	{
		// Arrange
		var proxy = OrionQL.wrap(Literals.asMap("foo", (Object) Literals.asMap("bar", "foobar")).add("key", "foo").add("subkey", "bar"), null);
		// Act & Assert
		assertThat("Incorrect indexing", proxy.access(":get(key#subkey)"), is("foobar"));
		assertThat("Incorrect indexing", proxy.access("foo:get(subkey)"), is("foobar"));
	}

	@Test
	public void test_sorting()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null);
		// Act & Assert
		assertThat("Incorrect sorting", proxy.access("assignedRoles:map(name):sort"), equalToObject(List.of("AR_10000029", "AR_10000044", "AR_10000060")));
		assertThat("Incorrect sorting", proxy.access("assignedRoles:map(name):sort(@ desc)"), equalToObject(List.of("AR_10000060", "AR_10000044", "AR_10000029")));
		assertThat("Incorrect sorting", proxy.access("assignedRoles:map(name):sort(:replace(2,8))"), equalToObject(List.of("AR_10000044", "AR_10000060", "AR_10000029")));
		assertThat("Incorrect sorting", proxy.access("assignedRoles:map(name):sort(:replace(2,8) desc)"), equalToObject(List.of("AR_10000029", "AR_10000060", "AR_10000044")));
		assertThat("Incorrect sorting", proxy.access("assignedRoles:sort(displayName:size):map(name)"), equalToObject(List.of("AR_10000044", "AR_10000060", "AR_10000029")));
		assertThat("Incorrect sorting", proxy.access("assignedRoles:sort(displayName:size desc):map(name)"), equalToObject(List.of("AR_10000029", "AR_10000060", "AR_10000044")));
		assertThat("Incorrect sorting", proxy.access("assignedRoles:sort(owner.name,displayName:size):map(name)"), equalToObject(List.of("AR_10000044", "AR_10000029", "AR_10000060")));
	}

	@Test
	public void test_upcase_downcase()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null);
		// Act & Assert
		assertThat("Incorrect conversion", proxy.access("firstname:upcase"), is("JOHN"));
		assertThat("Incorrect conversion", proxy.access("firstname:downcase"), is("john"));
	}

	@Test
	public void test_size()
	{
		// Arrange
		// Act & Assert
		assertThat("incorrect size", OrionQL.wrap(List.of("Hello", "world"), null).access(":size"), is(2));
		assertThat("incorrect size", OrionQL.wrap(Literals.asMap("foo", 1).add("bar", 2), null).access(":size"), is(2));
		assertThat("incorrect size", OrionQL.wrap("foo", null).access(":size"), is(3));
		assertThat("incorrect size", OrionQL.wrap((Iterable<String>) () -> new Iterator<>()
		{
			int num = 2;
			@Override
			public boolean hasNext()
			{
				return (num > 0);
			}

			@Override
			public String next()
			{
				--num;
				return null;
			}
		}, null).access(":size"), is(2));
		assertThat("incorrect size", OrionQL.wrap(1, null).access(":size"), is(nullValue()));
	}

	@Test
	public void test_select()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null);
		// Act & Assert
		assertThat("incorrect selection", proxy.access("assignedRoles:select(1)"), is(bundle_AR_10000029));
		assertThat("incorrect selection", proxy.access("assignedRoles:select(2):map(name)"), is(List.of("AR_10000029", "AR_10000044")));
		assertThat("incorrect selection", proxy.access("assignedRoles:select(1,eq(\"name\", \"AR_10000044\")).name"), is("AR_10000044"));
		assertThat("incorrect selection", proxy.access("assignedRoles:select(1,ne(\"name\", \"1) foo\")).name"), is("AR_10000029"));
		assertThat("incorrect selection", proxy.access("assignedRoles:select(1,not(ne(\"name\", \"AR_10000044\"))).name"), is("AR_10000044"));
		assertThat("incorrect selection", proxy.access("assignedRoles:select(1,eq(\"owner.name\", \"tk000\")).name"), is("AR_10000060"));
		assertThat("incorrect selection", proxy.access("assignedRoles:select(1,eq(\"application\\\\.name\", \"FIMS\")).name"), is("AR_10000044"));
		assertThat("incorrect selection", proxy.access("assignedRoles:select(1,eq(\"name\", \"AR_100000XX\"))"), is(nullValue()));
		assertThat("incorrect selection", proxy.access("assignedRoles:select(eq(\"name\", \"AR_100000XX\"))"), is(nullValue()));
		assertThat("incorrect selection", proxy.access("assignedRoles:select(eq(\"name\", \"AR_10000044\"))"), is(List.of(bundle_AR_10000044)));
		assertThat("incorrect selection", proxy.access("assignedRoles:select(like(\"displayName\", \" - \"))"), is(List.of(bundle_AR_10000029, bundle_AR_10000044)));
	}

	@Test
	public void test_switch()
	{
		// Arrange
		var proxy = OrionQL.wrap(identity, null);
		// Act & Assert
		assertThat("incorrect result", proxy.access("assignedRoles:map(:switch(name,60=owner.name,44=displayName,name))"), is(List.of("AR_10000029", "FIMS QA - Server Access-Applikationsadmin FIMS (ux)", "tk000")));
		assertThat("incorrect result", proxy.access("assignedRoles:map(name:switch(60=@.owner.name,44=@.displayName,@.name))"), is(List.of("AR_10000029", "FIMS QA - Server Access-Applikationsadmin FIMS (ux)", "tk000")));
		assertThat("incorrect result", proxy.access("assignedRoles:map(name:switch(60=\"That's it!\"))"), is(Arrays.asList(null, null, "That's it!")));
		assertThat("incorrect result", proxy.access("displayName:switch(.*\\,.*=\"comma\",\"plain\")"), is("plain"));
	}
}
