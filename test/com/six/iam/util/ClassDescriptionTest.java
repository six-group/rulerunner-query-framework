package com.six.iam.util;

import org.junit.Test;
import java.util.List;
import sailpoint.object.ProvisioningProject;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;

public class ClassDescriptionTest
{
	@Test
	public void test_name_mangling()
	{
		// Arrange
		var testee = ClassDescription.get(ProvisioningProject.class);
		// Act
		// Assert
		for (var name: List.of("attributes", "errorMessages", "expansionItems", "filtered", "iiqAccountRequest", "iiqPlan"))
		{
			assertThat("Attribute not detected: " + name, testee.getSimpleGetter(name), notNullValue());
		}
	}
}