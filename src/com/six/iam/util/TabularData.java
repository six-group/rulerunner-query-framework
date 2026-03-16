package com.six.iam.util;

import org.apache.commons.lang.StringEscapeUtils;
import java.io.*;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Map;

/**
 * Objects of this class represent a list of {@link OrionQL} expressions to be
 * used for creating tabular representations of object sequences in CSV or HTML
 * form.
 */
public class TabularData
{
	private final List<OrionQL.Accessor> columns = new ArrayList<>();
	private List<List<Object>> recordBuffer = null;
	private String csvFieldSeparator = ",";
	private String csvRecordSeparator = "\n";
	private boolean sanitizeHeaders = true;
	private boolean sanitizeCells = true;
	private Charset charset = null;
	private OutputStream outputStream = null;
	private int recordCount = 0;

	/**
	 * Variant of {@link #TabularData(List, boolean, Map)} that does not use a macro library
	 */
	public TabularData(List<String> columnDefinitions, boolean isTemplates)
	{
		this(columnDefinitions, isTemplates, Collections.emptyMap());
	}

	/**
	 * <p>Construct a TabularData from {@link OrionQL} column definitions of the following form:
	 * </p>
	 * <pre>  [columnName=]columnExpression</pre>
	 * <p>If not specified, <tt>columnName</tt> defaults to <tt>columnExpression</tt>.
	 * The expression may be empty if only the column headings need to be defined.
	 * </p>
	 * <p>examples:</p>
	 * <pre>  owner.name</pre>
	 * <pre>  Owner=owner.name</pre>
	 * <pre>  Notes=null</pre>
	 * <p>If <tt>isTemplates</tt> is <tt>true</tt>, the column expressions are
	 * expected as formatting templates, so the above examples would be written
	 * as:
	 * </p>
	 * <pre>  ${owner.name}</pre>
	 * <pre>  Owner=${owner.name}</pre>
	 * <pre>  Notes=</pre>
	 * @param columnDefinitions List of OrionQL column definitions
	 * @param isTemplates       if <tt>true</tt>, the column definitions contain template strings, else expressions
	 * @param macroLibrary      a Map that links OrionQL macro names to their expansions
	 */
	public TabularData(List<String> columnDefinitions, boolean isTemplates, Map<String, String> macroLibrary)
	{
		for (var definition: columnDefinitions)
		{
			if ((definition == null) || definition.isEmpty())
			{
				throw new RuntimeException("Empty column definition " + columns.size());
			}
			columns.add(OrionQL.compileAll(definition, null, null, isTemplates, macroLibrary).get(0));
		}
	}

	/**
	 * Alternative constructor, taking as input precompiled {@link OrionQL} column definitions
	 * @param accessors List of OrionQL precompiled column definitions
	 */
	public TabularData(List<OrionQL.Accessor> accessors)
	{
		columns.addAll(accessors);
	}

	/**
	 * Request records to be rendered as columns instead of rows when generating HTML
	 * output. This setting is silently ignored when generating CSV output.
	 */
	public TabularData setHorizontalLayout()
	{
		recordBuffer = new ArrayList<>();
		return this;
	}

	/**
	 * Set CSV field and record parameters. Default: Comma and Newline.
	 */
	public TabularData setCsvParameters(String fieldSeparator, String recordSeparator)
	{
		csvFieldSeparator = fieldSeparator;
		csvRecordSeparator = recordSeparator;
		return this;
	}

	/**
	 * Control escaping of column headers and cells when generating HTML.
	 * Default: Both <tt>true</tt>.
	 */
	@SuppressWarnings("unused")
	public TabularData setHtmlParameters(boolean sanitizeHeaders, boolean sanitizeCells)
	{
		this.sanitizeHeaders = sanitizeHeaders;
		this.sanitizeCells = sanitizeCells;
		return this;
	}

	/**
	 * Set output to go to the specified file. Can be used repeatedly and will reset
	 * the record count every time. If {@link #closeOutput()} is not invoked, the output
	 * stream must be closed externally.
	 */
	@SuppressWarnings("unused")
	public OutputStream openOutputFile(File path, String charset) throws FileNotFoundException
	{
		outputStream = new FileOutputStream(path);
		this.charset = Charset.forName(charset);
		recordCount = 0;
		return outputStream;
	}

	/**
	 * Set output to go to the specified {@link OutputStream}. Can be used repeatedly and
	 * will reset the record count every time. If {@link #closeOutput()} is not invoked,
	 * the output stream must be closed externally.
	 */
	public TabularData setOutputStream(OutputStream outputStream, String charset)
	{
		this.outputStream = outputStream;
		this.charset = Charset.forName(charset);
		recordCount = 0;
		return this;
	}

	/**
	 * Close and invalidate the current output stream
	 */
	public void closeOutput() throws IOException
	{
		if (outputStream == null)
		{
			throw new IOException("No output stream to close");
		}
		outputStream.close();
		outputStream = null;
	}

	private OutputStream getOutputStream()
	{
		if (outputStream == null)
		{
			throw new RuntimeException("No output stream to write to");
		}
		return outputStream;
	}

	/**
	 * Create a list of all column values from a name value map
	 */
	public List<Object> toList(Map<String, Object> fields)
	{
		var result = new ArrayList<>();
		for (var accessor: columns)
		{
			result.add(fields.get(accessor.getName()));
		}
		return result;
	}

	/**
	 * Create a list of all column values from a proxy (evaluating all
	 * column expressions)
	 */
	public <T> List<Object> toList(OrionQL.Proxy<T> data)
	{
		var result = new ArrayList<>();
		for (var accessor: columns)
		{
			result.add(accessor.access(data));
		}
		return result;
	}

	private static String quote(String name)
	{
		return String.format("\"%s\"", name.replace("\"", "\"\""));
	}

	/**
	 * Concatenate a value list into a CSV record using the defined field separator
	 */
	private String toCsvRecord(List<Object> fields)
	{
		var result = new StringBuilder();
		var values = fields.iterator();
		while (values.hasNext())
		{
			var value = values.next();
			if (value != null)
			{
				if ((value instanceof String) || (value instanceof OrionQL.HtmlContainer))
				{
					value = quote(value.toString());
				}
				else if (value instanceof Date)
				{
					value = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(value);
				}
				else
				{
					value = value.toString();
				}
				result.append(value);
			}
			if (values.hasNext())
			{
				result.append(csvFieldSeparator);
			}
		}
		return result.toString();
	}

	/**
	 * Concatenate the column names into a CSV file header line using the defined
	 * field separator and optionally prepending a prefix
	 */
	private String getCsvHeader(String prefix, boolean useQuoting)
	{
		var result = new StringBuilder();
		if (prefix != null)
		{
			result.append(prefix);
		}
		var accessors = columns.iterator();
		while (accessors.hasNext())
		{
			var title = accessors.next().getName();
			result.append(useQuoting ? quote(title) : title);
			if (accessors.hasNext())
			{
				result.append(csvFieldSeparator);
			}
		}
		return result.toString();
	}

	/**
	 * Write a CSV header line to output
	 */
	@SuppressWarnings("UnusedReturnValue")
	public TabularData writeCsvHeader(String prefix, boolean useQuoting) throws IOException
	{
		getOutputStream().write(getCsvHeader(prefix, useQuoting).getBytes(charset));
		getOutputStream().write(csvRecordSeparator.getBytes(charset));
		return this;
	}

	/**
	 * Write a CSV record to output
	 */
	public TabularData writeCsvRecord(List<Object> fields) throws IOException
	{
		getOutputStream().write(toCsvRecord(fields).getBytes(charset));
		getOutputStream().write(csvRecordSeparator.getBytes(charset));
		++recordCount;
		return this;
	}

	/**
	 * Write a CSV record to output
	 */
	@SuppressWarnings("unused")
	public TabularData writeCsvRecord(Map<String, Object> fields) throws IOException
	{
		return writeCsvRecord(toList(fields));
	}

	/**
	 * Write a CSV record to output
	 */
	@SuppressWarnings("UnusedReturnValue")
	public <T> TabularData writeCsvRecord(OrionQL.Proxy<T> data) throws IOException
	{
		return writeCsvRecord(toList(data));
	}

	/**
	 * Write to output CSV records for all objects returned by an iterator. Variant
	 * for an iterator yielding objects of type {@link OrionQL.Proxy}.
	 */
	public <T> void writeCsv(Iterator<OrionQL.Proxy<T>> input) throws IOException
	{
		while (input.hasNext())
		{
			writeCsvRecord(input.next());
		}
	}

	/**
	 * Write to output CSV records for all objects returned by an iterator. Variant for
	 * an iterator yielding plain objects (it has to be wrapped into an {@link OrionQL.Proxy}
	 * <i>itself</i> for that).
	 */
	public <T> void writeCsv(OrionQL.Proxy<Iterator<T>> input) throws IOException
	{
		var inputIterator = input.unwrap();
		while (inputIterator.hasNext())
		{
			writeCsvRecord(input.derive(inputIterator.next()));
		}
	}

	/**
	 * Concatenate a value list into an HTML table data row
	 */
	private String toHtmlRow(List<Object> fields)
	{
		var result = new StringBuilder();
		result.append("<tr>");
		var isRowHeader = (recordBuffer != null);
		for (var field : fields)
		{
			result.append(isRowHeader ? "<th>" : "<td>");
			var value = field;
			if (value != null)
			{
				if (value instanceof Date)
				{
					value = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(value);
				}
				else
				{
					value = value.toString();
				}
				if (isRowHeader ? sanitizeHeaders : sanitizeCells)
				{
					value = (field instanceof OrionQL.HtmlContainer) ?
							value :
							StringEscapeUtils.escapeHtml((String) value);
				}
				result.append(value);
			}
			result.append(isRowHeader ? "</th>" : "</td>");
			isRowHeader = false;
		}
		result.append("</tr>");
		return result.toString();
	}

	/**
	 * Concatenate the column names into an HTML table header row
	 */
	private String getHtmlHeader()
	{
		var result = new StringBuilder();
		result.append("<tr>");
		for (var accessor: columns)
		{
			result.append("<th>");
			var title = accessor.getName();
			if (sanitizeHeaders)
			{
				title = StringEscapeUtils.escapeHtml(title);
			}
			result.append(title);
			result.append("</th>");
		}
		result.append("</tr>");
		return result.toString();
	}

	/**
	 * Start HTML table output
	 */
	public void writeHtmlHeader() throws IOException
	{
		getOutputStream().write("<table>".getBytes(charset));
		if (recordBuffer == null)
		{
			getOutputStream().write("<thead>".getBytes(charset));
			getOutputStream().write(getHtmlHeader().getBytes(charset));
			getOutputStream().write("</thead>".getBytes(charset));
		}
		else
		{
			recordBuffer.clear();
		}
		getOutputStream().write("<tbody>".getBytes(charset));
	}

	/**
	 * Finish HTML table output
	 */
	public void writeHtmlFooter() throws IOException
	{
		if (recordBuffer != null)
		{
			var index = 0;
			for (var accessor: columns)
			{
				var fields = new ArrayList<>();
				fields.add(accessor.getName());
				for (var record : recordBuffer)
				{
					fields.add(record.get(index));
				}
				getOutputStream().write(toHtmlRow(fields).getBytes(charset));
				++index;
			}
			recordBuffer.clear();
		}
		getOutputStream().write("</tbody></table>".getBytes(charset));
	}

	/**
	 * Write an HTML table data row to output
	 */
	public TabularData writeHtmlRecord(List<Object> fields) throws IOException
	{
		if (recordBuffer == null)
		{
			getOutputStream().write(toHtmlRow(fields).getBytes(charset));
		}
		else
		{
			recordBuffer.add(fields);
		}
		++recordCount;
		return this;
	}

	/**
	 * Write an HTML table data row to output
	 */
	@SuppressWarnings({"unused", "UnusedReturnValue"})
	public TabularData writeHtmlRecord(Map<String, Object> fields) throws IOException
	{
		return writeHtmlRecord(toList(fields));
	}

	/**
	 * Write an HTML table data row to output
	 */
	@SuppressWarnings("UnusedReturnValue")
	public <T> TabularData writeHtmlRecord(OrionQL.Proxy<T> data) throws IOException
	{
		return writeHtmlRecord(toList(data));
	}

	/**
	 * Write to output an HTML table with all objects returned by an iterator. Variant
	 * for an iterator yielding objects of type {@link OrionQL.Proxy}.
	 */
	public <T> void writeHtmlTable(Iterator<OrionQL.Proxy<T>> input) throws IOException
	{
		writeHtmlHeader();
		while (input.hasNext())
		{
			writeHtmlRecord(input.next());
		}
		writeHtmlFooter();
	}

	/**
	 * Write to output an HTML table with all objects returned by an iterator. Variant
	 * for an iterator yielding plain objects (it has to be wrapped into an {@link OrionQL.Proxy}
	 * <i>itself</i> for that).
	 */
	public <T> void writeHtmlTable(OrionQL.Proxy<Iterator<T>> input) throws IOException
	{
		writeHtmlHeader();
		var inputIterator = input.unwrap();
		while (inputIterator.hasNext())
		{
			writeHtmlRecord(input.derive(inputIterator.next()));
		}
		writeHtmlFooter();
	}

	/**
	 * Return the number of records written to the current output stream
	 */
	public int getRecordCount()
	{
		return recordCount;
	}
}
