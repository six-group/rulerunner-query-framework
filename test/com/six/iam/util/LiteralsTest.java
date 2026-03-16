package com.six.iam.util;

import org.junit.Test;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class LiteralsTest
{
	@Test
	public void test_asMap()
	{
		// Arrange
		//   expected = {a=1, b=2, c={x=3, y=4}}
		var expected = new HashMap<>();
		expected.put("a", 1);
		expected.put("b", 2);
		var targetChild = new HashMap<>();
		expected.put("c", targetChild);
		targetChild.put("x", 3);
		targetChild.put("y", 4);
		// Act
		var testee = Literals
				.asMap("a", (Object) 1)
				.add("b", 2)
				.add("c", Literals
						.asMap("x", 3)
						.add("y", 4)
				);
		// Assert
		assertThat("Incorrect data", testee, is(expected));
	}

	@Test
	public void test_asSortedMap()
	{
		// Arrange
		var testee = Literals.asSortedMap("abc", "cde").add("cde", "efg").add("efg", "ghi").add("ghi", "ijk").add("ijk", "klm").add("klm", "mno").add("mno", "opq").add("opq", "qrs").add("qrs", "stu").add("stu", "uvw").add("uvw", "vwx").add("vwx", "xyz");
		// Assert & Assert
		assertThat("Incorrect data", testee.toString(), is("{abc=cde, cde=efg, efg=ghi, ghi=ijk, ijk=klm, klm=mno, mno=opq, opq=qrs, qrs=stu, stu=uvw, uvw=vwx, vwx=xyz}"));
	}

	@Test
	public void test_asDate()
	{
		// Arrange
		var dateString = "07.04.2022 16:49:13";
		var dateValue = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss").parse(dateString, new ParsePosition(0));
		// Act
		var testee = Literals.asDate("2022-04-07 16:49:13");
		// Assert
		assertThat("Incorrect data", testee, is(dateValue));
	}
}
