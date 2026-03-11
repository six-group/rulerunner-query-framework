package com.six.iam.util;

import org.junit.Test;
import java.util.*;
import sailpoint.api.PersistenceManager;
import sailpoint.object.*;
import sailpoint.tools.GeneralException;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.*;
import static com.six.iam.util.Literals.asMap;

public class QueryHelperTest
{
	public static final int COSTCENTER1 = 17;
	public static final int COSTCENTER2 = 27;

	private final QueryOptions queryOptions = new QueryOptions();
	private final List<Object[]> resultSet1 = List.of(
			record("John", "Doe", asMap("logonId", (Object) "tk001").add("costcenter", COSTCENTER1)),
			record("Hans", "Muster", asMap("logonId", (Object) "tk002").add("costcenter", COSTCENTER2))
	);
	private final List<Object[]> resultSet2 = List.of(
			record(asMap("logonId", (Object) "tk001").add("costcenter", COSTCENTER1)),
			record(asMap("logonId", (Object) "tk002").add("costcenter", COSTCENTER2))
	);

	private static Object[] record(Object... record)
	{
		return record;
	}

	@Test
	public void test_regular_attributes() throws GeneralException
	{
		// Arrange
		var context = mock(PersistenceManager.class);
		when(context.search(Identity.class, queryOptions, List.of("firstname", "lastname"))).thenReturn(resultSet1.iterator());
		// Act
		var iterator = QueryHelper.search(context, Identity.class, queryOptions, "firstname, lastname");
		// Assert
		assertThat("Incorrect data", iterator.next(), is(asMap("firstname", "John").add("lastname", "Doe")));
		assertThat("Incorrect data", iterator.next(), is(asMap("firstname", "Hans").add("lastname", "Muster")));
		assertThat("Too many rows", iterator.hasNext(), is(false));
	}

	@Test
	public void test_null() throws GeneralException
	{
		// Arrange
		var context = mock(PersistenceManager.class);
		when(context.search(Identity.class, queryOptions, List.of("firstname", "lastname"))).thenReturn(
				List.of(record(null, "Doe"), record("John", null)).iterator()
		);
		// Act
		var iterator = QueryHelper.search(context, Identity.class, queryOptions, "firstname, lastname");
		// Assert
		assertThat("Incorrect data", iterator.next(), is(asMap("lastname", "Doe")));
		assertThat("Incorrect data", iterator.next(), is(asMap("firstname", "John")));
		assertThat("Too many rows", iterator.hasNext(), is(false));
	}

	@Test
	public void test_regular_and_extended() throws GeneralException
	{
		// Arrange
		var context = mock(PersistenceManager.class);
		when(context.search(Identity.class, queryOptions, List.of("firstname", "lastname", "attributes"))).thenReturn(resultSet1.iterator());
		// Act
		// (the missing space before logonId is part of the test)
		var iterator = QueryHelper.search(context, Identity.class, queryOptions, "firstname, lastname,logonId");
		// Assert
		assertThat("Incorrect data", iterator.next(), is(asMap("firstname", "John").add("lastname", "Doe").add("logonId", "tk001")));
		assertThat("Incorrect data", iterator.next(), is(asMap("firstname", "Hans").add("lastname", "Muster").add("logonId", "tk002")));
		assertThat("Too many rows", iterator.hasNext(), is(false));
	}

	@Test
	public void test_regular_and_all_extended() throws GeneralException
	{
		// Arrange
		var context = mock(PersistenceManager.class);
		when(context.search(Identity.class, queryOptions, List.of("firstname", "lastname", "attributes"))).thenReturn(resultSet1.iterator());
		// Act
		var iterator = QueryHelper.search(context, Identity.class, queryOptions, "firstname, lastname,");
		// Assert
		assertThat("Incorrect data", iterator.next(), is(asMap("firstname", (Object) "John").add("lastname", "Doe").add("logonId", "tk001").add("costcenter", COSTCENTER1)));
		assertThat("Incorrect data", iterator.next(), is(asMap("firstname", (Object) "Hans").add("lastname", "Muster").add("logonId", "tk002").add("costcenter", COSTCENTER2)));
		assertThat("Too many rows", iterator.hasNext(), is(false));
	}

	@Test
	public void test_regular_and_attributes() throws GeneralException
	{
		// Arrange
		var context = mock(PersistenceManager.class);
		when(context.search(Identity.class, queryOptions, List.of("firstname", "lastname", "attributes"))).thenReturn(resultSet1.iterator());
		// Act
		var iterator = QueryHelper.search(context, Identity.class, queryOptions, "firstname, lastname, attributes");
		// Assert
		assertThat("Incorrect data", iterator.next(), is(asMap("firstname", (Object) "John").add("lastname", "Doe").add("attributes", asMap("logonId", (Object) "tk001").add("costcenter", COSTCENTER1))));
		assertThat("Incorrect data", iterator.next(), is(asMap("firstname", (Object) "Hans").add("lastname", "Muster").add("attributes", asMap("logonId", (Object) "tk002").add("costcenter", COSTCENTER2))));
		assertThat("Too many rows", iterator.hasNext(), is(false));
	}

	@Test
	public void test_regular_and_attributes_and_extended() throws GeneralException
	{
		// Arrange
		var context = mock(PersistenceManager.class);
		when(context.search(Identity.class, queryOptions, List.of("firstname", "lastname", "attributes"))).thenReturn(resultSet1.iterator());
		// Act
		var iterator = QueryHelper.search(context, Identity.class, queryOptions, "firstname, lastname, attributes, logonId");
		// Assert
		assertThat("Incorrect data", iterator.next(), is(asMap("firstname", (Object) "John").add("lastname", "Doe").add("attributes", asMap("logonId", (Object) "tk001").add("costcenter", COSTCENTER1)).add("logonId", "tk001")));
		assertThat("Incorrect data", iterator.next(), is(asMap("firstname", (Object) "Hans").add("lastname", "Muster").add("attributes", asMap("logonId", (Object) "tk002").add("costcenter", COSTCENTER2)).add("logonId", "tk002")));
		assertThat("Too many rows", iterator.hasNext(), is(false));
	}

	@Test
	public void test_extended_only() throws GeneralException
	{
		// Arrange
		var context = mock(PersistenceManager.class);
		when(context.search(Identity.class, queryOptions, List.of("attributes"))).thenReturn(resultSet2.iterator());
		// Act
		var iterator = QueryHelper.search(context, Identity.class, queryOptions, "logonId");
		// Assert
		assertThat("Incorrect data", iterator.next(), is(asMap("logonId", "tk001")));
		assertThat("Incorrect data", iterator.next(), is(asMap("logonId", "tk002")));
		assertThat("Too many rows", iterator.hasNext(), is(false));
	}

	@Test
	public void test_multiple_extended_only() throws GeneralException
	{
		// Arrange
		var context = mock(PersistenceManager.class);
		when(context.search(Identity.class, queryOptions, List.of("attributes"))).thenReturn(resultSet2.iterator());
		// Act
		var iterator = QueryHelper.search(context, Identity.class, queryOptions, "logonId, costcenter, rank");
		// Assert
		assertThat("Incorrect data", iterator.next(), is(asMap("logonId", (Object) "tk001").add("costcenter", COSTCENTER1)));
		assertThat("Incorrect data", iterator.next(), is(asMap("logonId", (Object) "tk002").add("costcenter", COSTCENTER2)));
		assertThat("Too many rows", iterator.hasNext(), is(false));
	}

	@Test
	public void test_all_extended_only() throws GeneralException
	{
		// Arrange
		var context = mock(PersistenceManager.class);
		when(context.search(Identity.class, queryOptions, List.of("attributes"))).thenReturn(resultSet2.iterator());
		// Act
		var iterator = QueryHelper.search(context, Identity.class, queryOptions, "");
		// Assert
		assertThat("Incorrect data", iterator.next(), is(asMap("logonId", (Object) "tk001").add("costcenter", COSTCENTER1)));
		assertThat("Incorrect data", iterator.next(), is(asMap("logonId", (Object) "tk002").add("costcenter", COSTCENTER2)));
		assertThat("Too many rows", iterator.hasNext(), is(false));
	}

	@Test
	public void test_extended_attribute_overridden_with_all() throws GeneralException
	{
		// Arrange
		var context = mock(PersistenceManager.class);
		when(context.search(Identity.class, queryOptions, List.of("attributes"))).thenReturn(resultSet2.iterator());
		// Act
		var iterator = QueryHelper.search(context, Identity.class, queryOptions, "rank,");
		// Assert
		assertThat("Incorrect data", iterator.next(), is(asMap("logonId", (Object) "tk001").add("costcenter", COSTCENTER1)));
		assertThat("Incorrect data", iterator.next(), is(asMap("logonId", (Object) "tk002").add("costcenter", COSTCENTER2)));
		assertThat("Too many rows", iterator.hasNext(), is(false));
	}

	@Test
	public void test_attributes_only() throws GeneralException
	{
		// Arrange
		var context = mock(PersistenceManager.class);
		when(context.search(Identity.class, queryOptions, List.of("attributes"))).thenReturn(resultSet2.iterator());
		// Act
		var iterator = QueryHelper.search(context, Identity.class, queryOptions, "attributes");
		// Assert
		assertThat("Incorrect data", iterator.next(), is(asMap("attributes", asMap("logonId", (Object) "tk001").add("costcenter", COSTCENTER1))));
		assertThat("Incorrect data", iterator.next(), is(asMap("attributes", asMap("logonId", (Object) "tk002").add("costcenter", COSTCENTER2))));
		assertThat("Too many rows", iterator.hasNext(), is(false));
	}

	@Test
	public void test_object_search() throws GeneralException
	{
		// Arrange
		var identity001 = new Identity();
		var identity003 = new Identity();
		var context = mock(PersistenceManager.class);
		when(context.search(Identity.class, queryOptions, "id")).thenReturn(List.of(new Object[]{"001"}, new Object[]{"002"}, new Object[]{"003"}).iterator());
		when(context.getObjectById(Identity.class, "001")).thenReturn(identity001);
		when(context.getObjectById(Identity.class, "003")).thenReturn(identity003);
		// Act
		var iterator = QueryHelper.search(context, Identity.class, queryOptions);
		// Assert
		assertThat("Incorrect data", iterator.hasNext(), is(true));
		assertThat("Incorrect data", iterator.hasNext(), is(true));
		assertThat("Incorrect data", iterator.hasNext(), is(true));
		assertThat("Incorrect data", iterator.next(), is(identity001));
		assertThat("Incorrect data", iterator.next(), is(identity003));
		assertThat("Incorrect data", iterator.hasNext(), is(false));
	}

//	// This needs Mockito 3.4
//	@Test
//	public void test_searchable_attribute() throws GeneralException
//	{
//		// Arrange
//		var identityorigin = mock(ObjectAttribute.class);
//		when(identityorigin.isSearchable()).thenReturn(true);
//		var department_nr = mock(ObjectAttribute.class);
//		when(department_nr.isSearchable()).thenReturn(false);
//		var objectConfig = mock(ObjectConfig.class);
//		when(objectConfig.getObjectAttribute("identityorigin")).thenReturn(identityorigin);
//		when(objectConfig.getObjectAttribute("department_nr")).thenReturn(department_nr);
//		// !!!mock static method ObjectConfig.getObjectConfig(Class) here!!!
//		var context = mock(PersistenceManager.class);
//		when(context.search(Identity.class, queryOptions, List.of("name", "identityorigin", "attributes"))).thenReturn(resultSet3.iterator());
//		// Act
//		var iterator = QueryHelper.search(context, Identity.class, queryOptions, "name, identityorigin, department_nr");
//		// Assert
//		assertThat("Incorrect data", iterator.next(), is(asMap("name", "tk001").add("identityorigin", "SIX").add("department_nr", "00001")));
//		assertThat("Incorrect data", iterator.next(), is(asMap("name", "tk002").add("identityorigin", "SPS").add("department_nr", "00002")));
//		assertThat("Too many rows", iterator.hasNext(), is(false));
//	}
}
