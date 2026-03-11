package com.six.iam.util;

import org.apache.bsf.BSFException;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import sailpoint.object.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class IndexedDataTest
{
	private final Identity johnDoe = createIdentity("tk001", "John", "Doe");
	private final Identity hansMuster = createIdentity("tk002", "Hans", "Muster");
	private final Identity petraMuster = createIdentity("tk003", "Petra", "Muster");
	private final List<Identity> identities = Arrays.asList(johnDoe, hansMuster, petraMuster);

	private static Identity createIdentity(String name, String firstname, String lastname)
	{
		var result = new Identity();
		result.setName(name);
		result.setFirstname(firstname);
		result.setLastname(lastname);
		return result;
	}

	@SuppressWarnings("unchecked")
	<T> T unwrap(Object proxy)
	{
		return ((OrionQL.Proxy<T>) proxy).unwrap();
	}

	@SuppressWarnings("unchecked")
	Object get(Object map, Object key)
	{
		return ((java.util.Map<Object, Object>) map).get(key);
	}

	@Test
	public void test_put()
	{
		// Arrange
		var index = new HashMap<String, Object>();
		var indexedData = new IndexedData<String, Identity>(index, List.of("name"), false);
		// Act
		indexedData.putAll(OrionQL.wrap(identities.iterator(), null));
		// Assert
		assertThat("Incorrect data", unwrap(index.get(johnDoe.getName())), is(johnDoe));
		assertThat("Incorrect data", unwrap(index.get(hansMuster.getName())), is(hansMuster));
		assertThat("Incorrect data", unwrap(index.get(petraMuster.getName())), is(petraMuster));
		assertThat("Incorrect count", indexedData.getObjectCount(), is(identities.size()));
	}

	@Test
	public void test_objects()
	{
		// Arrange
		var index = new HashMap<Identity, Object>();
		var indexedData = new IndexedData<Identity, Identity>(index, List.of("@"), false);
		// Act
		indexedData.countAll(OrionQL.wrap(identities.iterator(), null));
		indexedData.countAll(OrionQL.wrap(identities.iterator(), null));
		indexedData.count(OrionQL.wrap(createIdentity("tk001", "John", "Doe"), null));
		// Assert
		assertThat("Incorrect data", index.get(johnDoe), is(3));
		assertThat("Incorrect data", index.get(hansMuster), is(2));
		assertThat("Incorrect data", index.get(createIdentity("tk003", "Petra", "Muster")), is(2));
		assertThat("Incorrect count", indexedData.getObjectCount(), is(2 * identities.size() + 1));
	}

	@Test
	public void test_mapper()
	{
		// Arrange
		var index = new HashMap<String, Object>();
		var indexedData = new IndexedData<String, Identity>(index, List.of("name"), false).useMapper(OrionQL.compile("displayName", false)::access);
		// Act
		indexedData.putAll(OrionQL.wrap(identities.iterator(), null));
		// Assert
		assertThat("Incorrect data", index.get(johnDoe.getName()), is(johnDoe.getDisplayName()));
		assertThat("Incorrect data", index.get(hansMuster.getName()), is(hansMuster.getDisplayName()));
		assertThat("Incorrect data", index.get(petraMuster.getName()), is(petraMuster.getDisplayName()));
		assertThat("Incorrect count", indexedData.getObjectCount(), is(identities.size()));
	}

	@Test
	public void test_add()
	{
		// Arrange
		var index = new HashMap<String, Object>();
		var indexedData = new IndexedData<String, Identity>(index, List.of("lastname"), false).useMapper(OrionQL.Proxy::unwrap);
		// Act
		indexedData.addAll(OrionQL.wrap(identities.iterator(), null));
		// Assert
		assertThat("Incorrect data", index.get(johnDoe.getLastname()), is(List.of(johnDoe)));
		assertThat("Incorrect data", index.get(hansMuster.getLastname()), is(List.of(hansMuster, petraMuster)));
		assertThat("Incorrect count", indexedData.getObjectCount(), is(identities.size()));
	}

	@Test
	public void test_add_set()
	{
		// Arrange
		var index = new HashMap<String, Object>();
		var indexedData = new IndexedData<String, Identity>(index, List.of("lastname"), false).useCollection(HashSet::new).useMapper(OrionQL.Proxy::unwrap);
		// Act
		indexedData.addAll(OrionQL.wrap(identities.iterator(), null));
		// Assert
		assertThat("Incorrect data", index.get(johnDoe.getLastname()), is(new HashSet<>(List.of(johnDoe))));
		assertThat("Incorrect data", index.get(hansMuster.getLastname()), is(new HashSet<>(List.of(hansMuster, petraMuster))));
	}

	@Test
	public void test_count()
	{
		// Arrange
		var index = new HashMap<String, Object>();
		var indexedData = new IndexedData<String, Identity>(index, List.of("lastname"), false);
		// Act
		indexedData.countAll(OrionQL.wrap(identities.iterator(), null));
		// Assert
		assertThat("Incorrect data", index.get(johnDoe.getLastname()), is(1));
		assertThat("Incorrect data", index.get(hansMuster.getLastname()), is(2));
		assertThat("Incorrect count", indexedData.getObjectCount(), is(identities.size()));
	}

	@Test
	public void test_multilevel()
	{
		// Arrange
		var index = new HashMap<String, Object>();
		var indexedData = new IndexedData<String, Identity>(index, List.of("lastname", "firstname"), false).useMapper(OrionQL.Proxy::unwrap);
		// Act
		indexedData.putAll(OrionQL.wrap(identities.iterator(), null));
		// Assert
		assertThat("Incorrect data", get(index.get(johnDoe.getLastname()), johnDoe.getFirstname()), is(johnDoe));
		assertThat("Incorrect data", get(index.get(hansMuster.getLastname()), hansMuster.getFirstname()), is(hansMuster));
		assertThat("Incorrect data", get(index.get(petraMuster.getLastname()), petraMuster.getFirstname()), is(petraMuster));
		assertThat("Incorrect count", indexedData.getObjectCount(), is(identities.size()));
	}

	@Test
	public void test_get()
	{
		// Arrange
		var index = new HashMap<String, Object>();
		var indexedData = new IndexedData<String, Identity>(index, List.of("lastname", "firstname"), false).useMapper(OrionQL.Proxy::unwrap);
		// Act
		indexedData.putAll(OrionQL.wrap(identities.iterator(), null));
		// Assert
		assertThat("Incorrect data", indexedData.get(OrionQL.wrap(johnDoe, null)), is(johnDoe));
		assertThat("Incorrect data", indexedData.get(OrionQL.wrap(hansMuster, null)), is(hansMuster));
		assertThat("Incorrect data", indexedData.get(OrionQL.wrap(petraMuster, null)), is(petraMuster));
	}

	@Test
	public void test_peek()
	{
		// Arrange
		java.util.Map<String, Object> index = Literals.asMap("topLevel", Literals.asMap("secondLevel", 3));
		var indexedData = new IndexedData<String, Identity>(index, Collections.emptyList(), false);
		// Act & Assert
		assertThat("Incorrect result", indexedData.peek(), equalTo(new AbstractMap.SimpleEntry<>(2, 3)));
	}

	@Test
	public void test_overlay()
	{
		// Arrange
		//   target = {a=1, b=2, c={x=3, y=4}, d={z=5}, e={v=6}}
		var target = Literals
				.asMap("a", (Object) 1)
				.add("b", 2)
				.add("c", Literals.asMap("x", 3).add("y", 4))
				.add("d", Literals.asMap("z", 5))
				.add("e", Literals.asMap("v", 6))
				;
		//   source = {b=7, c={x=8}, e=null, f={u=9}}
		var sourceChild = Literals.asMap("u", 9);
		var source = Literals
				.asMap("b", (Object) 7)
				.add("c", Literals.asMap("x", 8))
				.add("e", null)
				.add("f", sourceChild)
				;
		//   expected = {a=1, b=7, c={x=8, y=4}, d={z=5}, e=null, f={u=9}}
		var expected = Literals
				.asMap("a", (Object) 1)
				.add("b", 7)
				.add("c", Literals.asMap("x", 8).add("y", 4))
				.add("d", Literals.asMap("z", 5))
				.add("e", null)
				.add("f", Literals.asMap("u", 9))
				;
		// Act
		IndexedData.overlay(target, source);
		sourceChild.put("v", 9);
		// Assert
		assertThat("Incorrect data", target, is(expected));
	}

	@Test
	public void test_flatten()
	{
		// Arrange
		var root = Literals
				.asMap("a", (Object) 1)
				.add("b", List.of(2, 3, 4))
				.add("c", Literals.asMap("x", (Object) 5).add("y", List.of(6, 7)))
				;
		var expected = new HashSet<>(Arrays.asList(1, 2, 3, 4, 5, 6, 7));
		// Act
		List<Integer> flattened = IndexedData.flatten(root);
		var testee = new HashSet<>(flattened);
		// Assert
		assertThat("Incorrect data", testee, is(expected));
	}

	@Test
	public void test_compress()
	{
		// Arrange
		var data = Literals
				.asMap((Object) "a string", (Object) "hello")
				.add("a number", 4)
				.add("a map", Literals.asMap("key", "value"))
				.add("path", Literals.asMap("to", Literals.asMap("map", Literals.asMap("string", "hello"))))
				.add("two", Literals.asMap("maps", Literals
						.asMap("first", (Object) Literals.asMap("key", "value"))
						.add("second", Literals.asMap("path", Literals.asMap("to", Literals.asMap("string", "hello"))))
						.add("third", "not a map")));
		var expected = Literals
				.asMap((Object) "a string", (Object) "hello")
				.add("a number", 4)
				.add("a map", Literals.asMap("key", "value"))
				.add("path/to/map", Literals.asMap("string", "hello"))
				.add("two/maps", Literals
						.asMap("first", (Object) Literals.asMap("key", "value"))
						.add("second/path/to", Literals.asMap("string", "hello"))
						.add("third", "not a map"));
		// Act
		var result = IndexedData.compress(data, "/");
		// Assert
		assertThat("Incorrect output", result, is(expected));
	}

	@Test
	public void test_transform() throws BSFException
	{
		// This test depends on OrionQL direct use instead of mocking it. (It
		// would be near to impossible to mock what is needed for exhaustive testing.)
		// Arrange
		//@formatter:off
		var input = OrionQL.wrap(Literals.asSortedMap(
				"l1k1", (Object) "abc").add(
				"l1k2", "cde").add(
				"l1k3", Literals.asSortedMap(
						"l2k1", (Object) "efg").add(
						"l2k2", List.of(
								"ghi",
								"ijk",
								List.of("klm", "mno")
						)).add(
						"l2k3", Literals.asSortedMap(
								ProvisioningPlan.Operation.Add, "opq").add(
								ProvisioningPlan.Operation.Remove, "qrs"
						)
				)
		), null);
		//@formatter:on
		// Act & Assert
		assertThat("Incorrect input",
				   input.unwrap().toString(),
				   is("{l1k1=abc, l1k2=cde, l1k3={l2k1=efg, l2k2=[ghi, ijk, [klm, mno]], l2k3={Add=opq, Remove=qrs}}}"));
		assertThat("Incorrect computation",
				   IndexedData.transform(input, CerberusLogic.compile("Level(1).reject()", "Level")).toString(),
				   is("{}"));
		assertThat("Incorrect computation",
				   IndexedData.transform(input, CerberusLogic.compile("Level(1).accept(like(\"value\", \"c\"))", "Level")).toString(),
				   is("{l1k1=abc, l1k2=cde}"));
		assertThat("Incorrect computation",
				   IndexedData.transform(input, CerberusLogic.compile("Level(2).accept(like(\"value\", \"g\"))", "Level")).toString(),
				   is("{l1k1=abc, l1k2=cde, l1k3={l2k1=efg, l2k2=[ghi, ijk, [klm, mno]]}}"));
		assertThat("Incorrect computation",
				   IndexedData.transform(input, CerberusLogic.compile("Level(1).accept(like(\"value\", \"g\"))", "Level")).toString(),
				   is("{l1k3={l2k1=efg, l2k2=[ghi, ijk, [klm, mno]], l2k3={Add=opq, Remove=qrs}}}"));
		assertThat("Incorrect computation",
				   IndexedData.transform(input, CerberusLogic.compile("Level(1).accept(like(\"value\", \"c\")); Level(0).accept(\":size\")", "Level")),
				   is(2));
		assertThat("Incorrect computation",
				   IndexedData.transform(input, CerberusLogic.compile("Level(1).accept(like(\"value\", \"c\")); Level(0).accept(\":index(>value,key)\")", "Level")).toString(),
				   is("{abc=l1k1, cde=l1k2}"));
		assertThat("Incorrect computation",
				   IndexedData.transform(input, CerberusLogic.compile("Level(3).accept(like(\"@\", \"g\", \"p\"))", "Level")).toString(),
				   is("{l1k1=abc, l1k2=cde, l1k3={l2k1=efg, l2k2=[ghi], l2k3={Add=opq}}}"));
		assertThat("Incorrect computation",
				   IndexedData.transform(input, CerberusLogic.compile("Level(3).accept(like(\"value?\", \"g\", \"p\"))", "Level")).toString(),
				   is("{l1k1=abc, l1k2=cde, l1k3={l2k1=efg, l2k3={Add=opq}}}"));
		assertThat("Incorrect computation",
				   IndexedData.transform(input, CerberusLogic.compile("Level(3).accept(like(\"value?|\", \"g\", \"p\"))", "Level")).toString(),
				   is("{l1k1=abc, l1k2=cde, l1k3={l2k1=efg, l2k2=[ghi], l2k3={Add=opq}}}"));
		assertThat("Incorrect computation",
				   IndexedData.transform(input, CerberusLogic.compile("Level(3).accept(like(\"@\", \"j\", \"l\"))", "Level")).toString(),
				   is("{l1k1=abc, l1k2=cde, l1k3={l2k1=efg, l2k2=[ijk, [klm, mno]]}}"));
		assertThat("Incorrect computation",
				   IndexedData.transform(input, CerberusLogic.compile("Level(3).accept(like(\"@\", \"x\"))", "Level")).toString(),
				   is("{l1k1=abc, l1k2=cde, l1k3={l2k1=efg}}"));
		assertThat("Incorrect computation",
				   IndexedData.transform(input, CerberusLogic.compile("Level(3).accept(like(\"@\", \"x\")); Level(2).accept()", "Level")).toString(),
				   is("{l1k1=abc, l1k2=cde, l1k3={l2k1=efg, l2k2=[], l2k3={}}}"));
		assertThat("Incorrect class",
				   IndexedData.transform(input, CerberusLogic.compile("Level(0).accept(\":index(>value:match(.*),key)\"); Level(1).reject()", "Level")).toString(),
				   is("{abc=l1k1, cde=l1k2, {l2k1=efg, l2k2=[ghi, ijk, [klm, mno]], l2k3={Add=opq, Remove=qrs}}=l1k3}"));
		assertThat("Incorrect computation",
				   IndexedData.transform(input, CerberusLogic.compile("Level(2).reject(); Level(0).accept(\":index(>value,key)\")", "Level")).toString(),
				   is("{abc=l1k1, cde=l1k2}"));
		assertThat("Incorrect computation",
				   IndexedData.transform(input, CerberusLogic.compile("Level(1).accept(\"value:size\", like(\"value\", \"c\"))", "Level")).toString(),
				   is("{l1k1=3, l1k2=3}"));
		assertThat("Incorrect computation",
				   IndexedData.transform(input, CerberusLogic.compile("Level(1).accept(\"value:size\", like(\"value\", \"c\")).accept()", "Level")).toString(),
				   is("{l1k1=3, l1k2=3, l1k3={l2k1=efg, l2k2=[ghi, ijk, [klm, mno]], l2k3={Add=opq, Remove=qrs}}}"));
		assertThat("Incorrect computation",
				   IndexedData.transform(input, CerberusLogic.compile("Level(1).accept(\"value:size\")", "Level")).toString(),
				   is("{l1k1=3, l1k2=3, l1k3=3}"));
		assertThat("Incorrect computation",
				   IndexedData.transform(input, CerberusLogic.compile("Level(2).accept(eq(\"key\", ref(\"^.key:replace(1,2)\")))", "Level")).toString(),
				   is("{l1k1=abc, l1k2=cde, l1k3={l2k3={Add=opq, Remove=qrs}}}"));
		assertThat("Incorrect computation",
				   IndexedData.transform(input, CerberusLogic.compile("Level(2).accept(eq(\"key\", ref(\"^.key:replace(1,2)\"))); Level(1).accept(\"value:match(.*):size\")", "Level")).toString(),
				   is("{l1k1=abc, l1k2=cde, l1k3=28}"));
		assertThat("Incorrect computation",
				   IndexedData.transform(input, CerberusLogic.compile("Level(3).accept(\"^.^.key\")", "Level")).toString(),
				   is("{l1k1=abc, l1k2=cde, l1k3={l2k1=efg, l2k2=[l1k3, l1k3, l1k3], l2k3={Add=l1k3, Remove=l1k3}}}"));
	}

	@Test
	public void test_pprint()
	{
		// Arrange
		var data = new TreeMap<String, Object>();
		var row = new TreeMap<String, Object>();
		row.put("col1", "r1c1");
		row.put("col2", List.of("r1c2<1>", "r1c2<2>"));
		data.put("row1", row);
		row = new TreeMap<>();
		row.put("col1", "r2c1");
		row.put("col3", 10);
		row.put("col4", Literals.asDate("2023-04-21 07:47:02"));
		row.put("multiline\nlabel", "multiline\ntext");
		data.put("row2", row);
		var testee = new IndexedData<String, String>(data, List.of(), false);
		// Act & Assert
		assertThat("Incorrect output", testee.pprint(-1, true), is("{\n    row1 = {\n        col1 = r1c1\n        col2 = [\n            r1c2<1>\n            r1c2<2>\n        ]\n    }\n    row2 = {\n        col1 = r2c1\n        col3 = 10\n        col4 = 2023-04-21 07:47:02\n        multiline\n        label = multiline\n                text\n    }\n}"));
		assertThat("Incorrect output", testee.pprint(0, true), is("{\n    row1                 = {\n        col1                 = r1c1\n        col2                 = [\n            r1c2<1>\n            r1c2<2>\n        ]\n    }\n    row2                 = {\n        col1                 = r2c1\n        col3                 = 10\n        col4                 = 2023-04-21 07:47:02\n        multiline\n        label                = multiline\n                               text\n    }\n}"));
		assertThat("Incorrect output", testee.pprint(0, false), is("row1                 = {\n    col1                 = r1c1\n    col2                 = [\n        r1c2<1>\n        r1c2<2>\n    ]\n}\nrow2                 = {\n    col1                 = r2c1\n    col3                 = 10\n    col4                 = 2023-04-21 07:47:02\n    multiline\n    label                = multiline\n                           text\n}"));
		assertThat("Incorrect output", IndexedData.pprint(new HashMap<>(), -1, true), is("{\n}"));
		assertThat("Incorrect output", IndexedData.pprint(new HashMap<>(), -1, false), is(""));
	}

	@Test
	public void test_pprint_nulls()
	{
		// Arrange
		var data = new HashMap<String, Object>();
		data.put("key", null);
		data.put(null, "value");
		var testee = new IndexedData<String, String>(data, List.of(), false);
		// Act & Assert
		assertThat("Incorrect output", testee.pprint(-1, true), is("{\n    null = value\n    key = null\n}"));
	}

	private String writeHtmlMatrix(IndexedData<String, String> testee, IndexedData.CellMapper cellMapper, boolean doTotals) throws IOException
	{
		var result = new ByteArrayOutputStream();
		testee.writeHtmlMatrix(result, "UTF-8", cellMapper, doTotals, false);
		return result.toString(StandardCharsets.UTF_8);
	}

	@SuppressWarnings("SameParameterValue")
	private String writeHtmlMatrices(int numCaptionLevels, IndexedData<String, String> testee, IndexedData.CellMapper cellMapper, boolean doTotals, boolean addNavigation) throws IOException
	{
		var result = new ByteArrayOutputStream();
		testee.writeHtmlMatrices(numCaptionLevels, result, "UTF-8", cellMapper, doTotals, false, addNavigation);
		return result.toString(StandardCharsets.UTF_8);
	}

	@Test
	public void test_html_matrix() throws IOException
	{
		// Arrange
		//@formatter:off
		var data = Literals.asSortedMap(
				"<row1>", (Object) Literals.asSortedMap(
						"<col1>", List.of("r1c1")).add(
						"col2", List.of("r1c2<1>", "r1c2<2>"))).add(
				"row2", Literals.asSortedMap(
						"<col1>", List.of("r2c1")).add(
						"col3", List.of("r2c3")));
		var outer = Literals.asMap("Heading", (Object) data);
		//@formatter:on
		var testee = new IndexedData<String, String>(data, List.of(), false);
		// Act & Assert
		assertThat("Incorrect matrix", writeHtmlMatrix(testee, null, false), is("<table><thead><tr><th></th><th>&lt;col1&gt;</th><th>col2</th><th>col3</th></tr></thead><tbody><tr><th>&lt;row1&gt;</th><td>[r1c1]</td><td>[r1c2&lt;1&gt;, r1c2&lt;2&gt;]</td><td></td></tr><tr><th>row2</th><td>[r2c1]</td><td></td><td>[r2c3]</td></tr></tbody></table>"));
		assertThat("Incorrect matrix", writeHtmlMatrix(testee, IndexedData.CellMapper.JOIN, false), is("<table><thead><tr><th></th><th>&lt;col1&gt;</th><th>col2</th><th>col3</th></tr></thead><tbody><tr><th>&lt;row1&gt;</th><td>r1c1</td><td>r1c2&lt;1&gt;\nr1c2&lt;2&gt;</td><td></td></tr><tr><th>row2</th><td>r2c1</td><td></td><td>r2c3</td></tr></tbody></table>"));
		assertThat("Incorrect matrix", writeHtmlMatrix(testee, new IndexedData.CellMapper(OrionQL.compile(":join( & )", false)), false), is("<table><thead><tr><th></th><th>&lt;col1&gt;</th><th>col2</th><th>col3</th></tr></thead><tbody><tr><th>&lt;row1&gt;</th><td>r1c1</td><td>r1c2&lt;1&gt; &amp; r1c2&lt;2&gt;</td><td></td></tr><tr><th>row2</th><td>r2c1</td><td></td><td>r2c3</td></tr></tbody></table>"));
		testee.set(outer);
		assertThat("Incorrect matrix", writeHtmlMatrix(testee, IndexedData.CellMapper.PPRINT, false), is("<table><thead><tr><th></th><th>row2</th><th>&lt;row1&gt;</th></tr></thead><tbody><tr><th>Heading</th><td>&lt;col1&gt; = [\n    r2c1\n]\ncol3 = [\n    r2c3\n]</td><td>&lt;col1&gt; = [\n    r1c1\n]\ncol2 = [\n    r1c2&lt;1&gt;\n    r1c2&lt;2&gt;\n]</td></tr></tbody></table>"));
	}

	@Test
	public void test_html_matrix_totals() throws IOException
	{
		// Arrange
		//@formatter:off
		var data = Literals.asSortedMap("row1", (Object) Literals.asSortedMap("column1", 5));
		//@formatter:on
		var testee = new IndexedData<String, String>(data, List.of(), false);
		// Act & Assert
		assertThat("Incorrect matrix", writeHtmlMatrix(testee, null, true), is("<table><thead><tr><th></th><th>column1</th></tr></thead><tbody><tr><th>row1</th><td class=\"numeric\">5</td></tr><tr><th>TOTAL</th><td class=\"numeric\">5</td></tr></tbody></table>"));
		IndexedData.put(data, 3, "row2", "column2");
		assertThat("Incorrect matrix", writeHtmlMatrix(testee, null, true), is("<table><thead><tr><th></th><th>column1</th><th>column2</th><th>TOTAL</th></tr></thead><tbody><tr><th>row1</th><td class=\"numeric\">5</td><td class=\"numeric\"></td><td class=\"numeric\">5</td></tr><tr><th>row2</th><td class=\"numeric\"></td><td class=\"numeric\">3</td><td class=\"numeric\">3</td></tr><tr><th>TOTAL</th><td class=\"numeric\">5</td><td class=\"numeric\">3</td><td class=\"numeric\">8</td></tr></tbody></table>"));
		IndexedData.put(data, 5.0, "row1", "column1");
		assertThat("Incorrect matrix", writeHtmlMatrix(testee, null, true), is("<table><thead><tr><th></th><th>column1</th><th>column2</th><th>TOTAL</th></tr></thead><tbody><tr><th>row1</th><td class=\"numeric\">5.0</td><td class=\"numeric\"></td><td class=\"numeric\">5.00</td></tr><tr><th>row2</th><td class=\"numeric\"></td><td class=\"numeric\">3</td><td class=\"numeric\">3.00</td></tr><tr><th>TOTAL</th><td class=\"numeric\">5.00</td><td class=\"numeric\">3.00</td><td class=\"numeric\">8.00</td></tr></tbody></table>"));
		IndexedData.put(data, new OrionQL.HtmlContainer("3", 3), "row2", "column2");
		assertThat("Incorrect matrix", writeHtmlMatrix(testee, null, true), is("<table><thead><tr><th></th><th>column1</th><th>column2</th><th>TOTAL</th></tr></thead><tbody><tr><th>row1</th><td class=\"numeric\">5.0</td><td class=\"numeric\"></td><td class=\"numeric\">5.00</td></tr><tr><th>row2</th><td class=\"numeric\"></td><td class=\"numeric\">3</td><td class=\"numeric\">3.00</td></tr><tr><th>TOTAL</th><td class=\"numeric\">5.00</td><td class=\"numeric\">3.00</td><td class=\"numeric\">8.00</td></tr></tbody></table>"));
	}

	@Test
	public void test_html_matrix_header_suppression() throws IOException
	{
		// Arrange
		//@formatter:off
		var data = Literals.asSortedMap("row", (Object) Literals.asSortedMap("", 5));
		//@formatter:on
		var testee = new IndexedData<String, String>(data, List.of(), false);
		// Act & Assert
		assertThat("Incorrect matrix", writeHtmlMatrix(testee, null, false), is("<table><tbody><tr><th>row</th><td>5</td></tr></tbody></table>"));
		assertThat("Incorrect matrix", writeHtmlMatrix(testee, null, true), is("<table><tbody><tr><th>row</th><td class=\"numeric\">5</td></tr><tr><th>TOTAL</th><td class=\"numeric\">5</td></tr></tbody></table>"));
	}

	@Test
	public void test_html_matrices() throws IOException
	{
		// Arrange
		//@formatter:off
		var matrix = new TreeMap<>(Literals.asMap("row", (Object) Literals.asMap("column", "*")));
		var data = Literals.asSortedMap(
				"section1", (Object) Literals.asSortedMap(
						"section11", matrix).add(
						"section12", matrix)).add(
				"section2", Literals.asSortedMap(
						"section21", matrix).add(
						"section22", matrix));
		//@formatter:on
		var testee = new IndexedData<String, String>(data, List.of(), false);
		// Act & Assert
		assertThat("Incorrect HTML", writeHtmlMatrices(2, testee, null, false, true), is("\n<h2 title=\"section1\">section1<a name=\"/section1\"></a></h2>\n<h3 title=\"section1 &rtrif; section11\">section11<a name=\"/section1/section11\"></a></h3>\n<table><thead><tr><th></th><th>column</th></tr></thead><tbody><tr><th>row</th><td>*</td></tr></tbody></table>\n<h3 title=\"section1 &rtrif; section12\">section12<a name=\"/section1/section12\"></a></h3>\n<table><thead><tr><th></th><th>column</th></tr></thead><tbody><tr><th>row</th><td>*</td></tr></tbody></table>\n<h2 title=\"section2\">section2<a name=\"/section2\"></a></h2>\n<h3 title=\"section2 &rtrif; section21\">section21<a name=\"/section2/section21\"></a></h3>\n<table><thead><tr><th></th><th>column</th></tr></thead><tbody><tr><th>row</th><td>*</td></tr></tbody></table>\n<h3 title=\"section2 &rtrif; section22\">section22<a name=\"/section2/section22\"></a></h3>\n<table><thead><tr><th></th><th>column</th></tr></thead><tbody><tr><th>row</th><td>*</td></tr></tbody></table>\n<div id=\"navtoggle\">&target;</div><div id=\"navigator\"><h3>Navigation</h3><input id=\"section1\" type=\"checkbox\" checked/><div><label for=\"section1\" class=\"toggle\"></label><a title=\"section1\" href=\"#/section1\">section1</a></div><div class=\"folder\"><div><label></label><a title=\"section1 &rtrif; section11\" href=\"#/section1/section11\">section11</a></div><div><label></label><a title=\"section1 &rtrif; section12\" href=\"#/section1/section12\">section12</a></div></div><input id=\"section2\" type=\"checkbox\" checked/><div><label for=\"section2\" class=\"toggle\"></label><a title=\"section2\" href=\"#/section2\">section2</a></div><div class=\"folder\"><div><label></label><a title=\"section2 &rtrif; section21\" href=\"#/section2/section21\">section21</a></div><div><label></label><a title=\"section2 &rtrif; section22\" href=\"#/section2/section22\">section22</a></div></div></div>"));
		assertThat("Incorrect HTML", writeHtmlMatrices(2, testee, null, false, false), is("\n<h2 title=\"section1\">section1</h2>\n<h3 title=\"section1 &rtrif; section11\">section11</h3>\n<table><thead><tr><th></th><th>column</th></tr></thead><tbody><tr><th>row</th><td>*</td></tr></tbody></table>\n<h3 title=\"section1 &rtrif; section12\">section12</h3>\n<table><thead><tr><th></th><th>column</th></tr></thead><tbody><tr><th>row</th><td>*</td></tr></tbody></table>\n<h2 title=\"section2\">section2</h2>\n<h3 title=\"section2 &rtrif; section21\">section21</h3>\n<table><thead><tr><th></th><th>column</th></tr></thead><tbody><tr><th>row</th><td>*</td></tr></tbody></table>\n<h3 title=\"section2 &rtrif; section22\">section22</h3>\n<table><thead><tr><th></th><th>column</th></tr></thead><tbody><tr><th>row</th><td>*</td></tr></tbody></table>\n"));
	}

	@SuppressWarnings("SameParameterValue")
	private String writeHtmlTables(IndexedData<String, Identity> testee, TabularData writer, boolean addNavigation) throws IOException
	{
		var result = new ByteArrayOutputStream();
		testee.writeHtmlTables(result, "UTF-8", writer, addNavigation);
		return result.toString(StandardCharsets.UTF_8);
	}

	@Test
	public void test_html_tables() throws IOException
	{
		// Arrange
		var testee = new IndexedData<String, Identity>(new TreeMap<>(), List.of("lastname"), false);
		testee.addAll(OrionQL.wrap(identities.iterator(), null));
		var tabularData = new TabularData(List.of("firstname", "lastname"), false);
		// Act & Assert
		assertThat("Incorrect HTML", writeHtmlTables(testee, tabularData, true), is("\n<h2 title=\"Doe\">Doe<a name=\"/Doe\"></a></h2>\n<table><thead><tr><th>firstname</th><th>lastname</th></tr></thead><tbody><tr><td>John</td><td>Doe</td></tr></tbody></table>\n<h2 title=\"Muster\">Muster<a name=\"/Muster\"></a></h2>\n<table><thead><tr><th>firstname</th><th>lastname</th></tr></thead><tbody><tr><td>Hans</td><td>Muster</td></tr><tr><td>Petra</td><td>Muster</td></tr></tbody></table>\n<div id=\"navtoggle\">&target;</div><div id=\"navigator\"><h3>Navigation</h3><div><label></label><a title=\"Doe\" href=\"#/Doe\">Doe</a></div><div><label></label><a title=\"Muster\" href=\"#/Muster\">Muster</a></div></div>"));
	}

	@Test
	public void test_html_totals() throws IOException
	{
		// Arrange
		//@formatter:off
		var data = new TreeMap<>(Literals.asMap(
				"row1", (Object) Literals.asMap(
						"col1", 1).add(
						"col2", 2)).add(
				"row2", Literals.asMap(
						"col1", 10).add(
						"col3", 2)));
		//@formatter:on
		var testee = new IndexedData<String, String>(data, List.of(), false);
		// Act & Assert
		assertThat("Incorrect matrix", writeHtmlMatrix(testee, null, true), is("<table><thead><tr><th></th><th>col1</th><th>col2</th><th>col3</th><th>TOTAL</th></tr></thead><tbody><tr><th>row1</th><td class=\"numeric\">1</td><td class=\"numeric\">2</td><td class=\"numeric\"></td><td class=\"numeric\">3</td></tr><tr><th>row2</th><td class=\"numeric\">10</td><td class=\"numeric\"></td><td class=\"numeric\">2</td><td class=\"numeric\">12</td></tr><tr><th>TOTAL</th><td class=\"numeric\">11</td><td class=\"numeric\">2</td><td class=\"numeric\">2</td><td class=\"numeric\">15</td></tr></tbody></table>"));
	}
}
