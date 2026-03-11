package com.six.iam.util;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import sailpoint.object.Identity;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class TabularDataTest
{
	private final Identity johnDoe = createIdentity("tk001", "John", "Doe", Literals.asDate("2022-04-07 16:49:13"));
	private final Identity hansMuster = createIdentity("tk002", "Hans", "Muster", Literals.asDate("2022-04-08 00:00:00"));
	private final List<Identity> identities = Arrays.asList(johnDoe, hansMuster);
	private final List<OrionQL.Proxy<Identity>> proxiedIdentities = Arrays.asList(
			OrionQL.wrap(johnDoe, null),
			OrionQL.wrap(hansMuster, null)
	);

	private static Identity createIdentity(String name, String firstname, String lastname, Date startDate)
	{
		var result = new Identity();
		result.setName(name);
		result.setFirstname(firstname);
		result.setLastname(lastname);
		result.setAttribute("startDate", startDate);
		return result;
	}

	@Test
	public void test_list()
	{
		// Arrange
		var tabularData = new TabularData(Arrays.asList("logonid=name", "displayName", "signature=null"), false);
		var map = Literals.asMap("logonid", (Object) johnDoe.getName()).add("displayName", johnDoe.getDisplayName()).add("signature", null);
		var list = Arrays.asList(johnDoe.getName(), johnDoe.getDisplayName(), null);
		// Act & Assert
		assertThat("Incorrect data", tabularData.toList(map), is(list));
		assertThat("Incorrect data", tabularData.toList(OrionQL.wrap(johnDoe, null)), is(list));
	}

	@Test
	public void test_csv_output() throws IOException
	{
		// Arrange
		var tabularData = new TabularData(Arrays.asList("logonid=name", "displayName", "signature=null", "startDate"), false);
		var expected1 = "\"logonid\",\"displayName\",\"signature\",\"startDate\"\n\"tk001\",\"John Doe\",,2022-04-07T16:49:13\n\"tk002\",\"Hans Muster\",,2022-04-08T00:00:00\n";
		var expected2 = "#logonid;displayName;signature;startDate\r\n\"tk001\";\"John Doe\";;2022-04-07T16:49:13\r\n\"tk002\";\"Hans Muster\";;2022-04-08T00:00:00\r\n";
		// Act
		var output_1 = new ByteArrayOutputStream();
		tabularData.setOutputStream(output_1, "Windows-1251");
		tabularData.writeCsvHeader(null, true);
		tabularData.writeCsv(OrionQL.wrap(identities.iterator(), null));
		var output_2 = new ByteArrayOutputStream();
		tabularData.setCsvParameters(";", "\r\n").setOutputStream(output_2, "Windows-1251");
		tabularData.writeCsvHeader("#", false);
		tabularData.writeCsv(proxiedIdentities.iterator());
		// Assert
		assertThat("Incorrect output", output_1.toString("Windows-1251"), is(expected1));
		assertThat("Incorrect output", output_2.toString("Windows-1251"), is(expected2));
		assertThat("Incorrect count", tabularData.getRecordCount(), is(2));
	}

	@Test
	public void test_html_output() throws IOException
	{
		// Arrange
		var tabularData = new TabularData(Arrays.asList("logonid=name", "displayName", "&oslash;=null", "startDate"), false);
		var expected_1 = String.join("",
								   "<table><thead>",
								   "<tr><th>logonid</th><th>displayName</th><th>&amp;oslash;</th><th>startDate</th></tr></thead><tbody>",
								   "<tr><td>tk001</td><td>John Doe</td><td></td><td>2022-04-07 16:49:13</td></tr>",
								   "<tr><td>tk002</td><td>Hans Muster</td><td></td><td>2022-04-08 00:00:00</td></tr>",
								   "</tbody></table>"
		);
		var expected_2 = String.join("",
								   "<table><tbody>",
								   "<tr><th>logonid</th><td>tk001</td><td>tk002</td></tr>",
								   "<tr><th>displayName</th><td>John Doe</td><td>Hans Muster</td></tr>",
								   "<tr><th>&oslash;</th><td></td><td></td></tr>",
								   "<tr><th>startDate</th><td>2022-04-07 16:49:13</td><td>2022-04-08 00:00:00</td></tr>",
								   "</tbody></table>"
		);
		// Act
		var output_1 = new ByteArrayOutputStream();
		tabularData.setOutputStream(output_1, "UTF-8");
		tabularData.writeHtmlTable(OrionQL.wrap(identities.iterator(), null));
		var output_2 = new ByteArrayOutputStream();
		tabularData.setOutputStream(output_2, "UTF-8");
		tabularData.writeHtmlTable(proxiedIdentities.iterator());
		var output_3 = new ByteArrayOutputStream();
		tabularData.setHorizontalLayout().setHtmlParameters(false, true).setOutputStream(output_3, "UTF-8");
		tabularData.writeHtmlTable(OrionQL.wrap(identities.iterator(), null));
		// Assert
		assertThat("Incorrect output", output_1.toString(), is(expected_1));
		assertThat("Incorrect output", output_2.toString(), is(expected_1));
		assertThat("Incorrect output", output_3.toString(), is(expected_2));
	}
}
