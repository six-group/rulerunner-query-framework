package com.six.iam.iiq.rulerunner.rules;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import com.six.iam.iiq.rulerunner.RuleRunnerBean;
import com.six.iam.util.Literals;
import com.six.iam.util.TabularData;
import sailpoint.object.*;

public class ParseFile implements JavaRuleExecutor
{
	// builtin parser definitions
	private final static Map<String, String> knownTypes = Literals
			.asMap("tomcat.log", "(?<timestamp>[-:,T0-9]+) +(?<level>[A-Z]+) (?<thread>[^:]+) (?<class>[^:]*):(?<line>[^ ]*) - (?<message>.*)")
			.add("iiq.log", "A,(?<timestamp>[-:.T0-9]+),(?<level>[A-Z]+),iam,app,\\[(?<thread>[^\\]]+)\\]-\\((?<class>[^:]*):(?<line>[^\\)]*)\\):(?<message>.*)")
			.add("batch.log", "A,(?<timestamp>[-+:.T0-9]+),(?<level>[A-Z]+),(?<server>[^,]+),iiq-batch,(?<job>[^,]+),(?<message>.*)")
			.add("access.log", "(?<client>[^ ]+)[^\\[]+\\[(?<timestamp>[^\\]]+)\\] *\"(?<method>[A-Z]+)? *(?<path>[^\"]*)\" *(?<status>[0-9]+) *(?<length>.+)")
			.add("apache-error.log", "\\[(?<timestamp>[^\\]]+)\\] *\\[(?<category>[^\\]]+)\\] *\\[pid *(?<pid>[^:]+):tid *(?<tid>[^\\]]+)\\] *\\[client *(?<client>[^\\]]+)\\] *(?<error>[^:]+): *(?<message>.*)")
			.add("meminfo.log", "\\[(?<timestamp>[^]]+)\\] host = (?<host>[^ ]+) / max memory = (?<max>[^ ]+) / total memory = (?<total>[^ ]+) / free memory = (?<free>[^ ]+) / used memory = (?<used>[^ ]+) / available memory = (?<available>[^ ]+) / cpu load = (?<cpu>[^ ]+) %")
			.add("properties", "(?<key>[^=]+)=(?<value>.*)(?<overflow>.*)")
			.add("plain", "(?<line>.*)");

	private static class RecordsIterator implements Iterator<Map<String, Object>>
	{
		private final List<String> groupNames;
		private final String lastGroupName;
		private long skip;
		private final Pattern lineParser;
		private final Pattern filter;
		private final Pattern startFinder;
		private final Pattern stopFinder;
		private final int maximumRecords;
		private int lineCount = 0;
		private int linesExamined = 0;
		private int linesUsed = 0;
		private int numRecords = 0;
		boolean started;
		boolean stopped = false;
		private Map<String, Object> record = null;
		private Map<String, Object> nextRecord = null;
		private List<String> lastGroupData = null;
		private boolean lineParserMatched = false;
		private String path = null;
		private BufferedReader inputReader = null;
		private final List<String> files;
		private final Iterator<String> inputs;
		private final RuleRunnerBean.RuleRunnerOutput rulerunnerOutput;

		private RecordsIterator(RuleRunnerBean.RuleRunnerOutput rulerunnerOutput, List<String> groupNames, Pattern lineParser, long skip, Pattern filter, Pattern startFinder, Pattern stopFinder, int maximumRecords, List<String> files)
		{
			this.rulerunnerOutput = rulerunnerOutput;
			this.groupNames = new ArrayList<>(groupNames);
			this.lastGroupName = this.groupNames.remove(this.groupNames.size() - 1);
			this.skip = skip;
			this.lineParser = lineParser;
			this.filter = filter;
			this.startFinder = startFinder;
			started = (startFinder == null);
			this.stopFinder = stopFinder;
			this.maximumRecords = maximumRecords;
			this.files = files;
			this.inputs = files.iterator();
		}

		public boolean hasNext()
		{
			if (record != null)
			{
				return true;
			}
			if (stopped)
			{
				return false;
			}
			if ((maximumRecords != 0) && (numRecords >= maximumRecords))
			{
				if (rulerunnerOutput != null)
				{
					rulerunnerOutput.addMessage("Maximum row count was reached");
				}
				stopped = true;
				return false;
			}
			try
			{
				// read lines from input until we have a record
				while (true)
				{
					var line = readNextLine();
					if (line == null)
					{
						break;
					}
					++linesExamined;
					var matcher = lineParser.matcher(line);
					if (matcher.matches())
					{
						lineParserMatched = true;
						if (started && (nextRecord != null))
						{
							// record start ends current record
							finishRecord();
						}
						startRecord(matcher);
					}
					else if (!lineParserMatched && (linesExamined > 10000))
					{
						throw new RuntimeException("Parser doesn't match any line");
					}
					else if (lastGroupData != null)
					{
						lastGroupData.add(line);
					}
					// check line for start and stop patterns (valid for nextRecord)
					if (!started && startFinder.matcher(line).find())
					{
						started = true;
					}
					if (started && !stopped && (nextRecord != null) && (stopFinder != null) && stopFinder.matcher(line).find())
					{
						stopped = true;
					}
					if (record != null)
					{
						return true;
					}
				}
			}
			catch (IOException e)
			{
				throw new RuntimeException(e);
			}
			// end of last input file or stopped explicitly
			if (started && (nextRecord != null))
			{
				// end of last input file ends record
				finishRecord();
			}
			closeInput();
			stopped = true;
			return (record != null);
		}

		public Map<String, Object> next()
		{
			if (!hasNext())
			{
				throw new NoSuchElementException();
			}
			var result = record;
			record = null;
			return result;
		}

		/**
		 * Read next line of input, open files as necessary
		 */
		private String readNextLine() throws IOException
		{
			while (true)
			{
				if ((inputReader == null) || !inputReader.ready())
				{
					closeInput();
					if (!inputs.hasNext())
					{
						stopped = true;
						return null;
					}
					// open next input file
					path = inputs.next();
					var inputStream = openInputStream(path);
					if (path.endsWith(".gz"))
					{
						inputStream = new GZIPInputStream(inputStream);
					}
					// skip is applied to the first file only
					while (skip > 0)
					{
						skip -= inputStream.skip(skip);
					}
					inputReader = new BufferedReader(new InputStreamReader(inputStream));
					continue;
				}
				var line = inputReader.readLine();
				if (line == null)
				{
					closeInput();
					continue;
				}
				++lineCount;
				if ((filter == null) || filter.matcher(line).find())
				{
					return line;
				}
			}
		}

		/**
		 * Safe close current input
		 */
		private void closeInput()
		{
			if (inputReader != null)
			{
				try
				{
					inputReader.close();
				}
				catch (IOException e)
				{
					// safe close
				}
			}
			inputReader = null;
		}

		private void startRecord(Matcher matcher)
		{
			nextRecord = new HashMap<>();
			for (var name : groupNames)
			{
				nextRecord.put(name, matcher.group(name));
			}
			if (files.size() > 1)
			{
				nextRecord.put("_", path);
			}
			lastGroupData = new ArrayList<>();
			lastGroupData.add(matcher.group(lastGroupName));
		}

		private void finishRecord()
		{
			nextRecord.put(lastGroupName, String.join("\n", lastGroupData));
			linesUsed += lastGroupData.size();
			record = nextRecord;
			++numRecords;
		}
	}

	@Override
	public Object execute(JavaRuleContext ruleContext)
	{
		var arguments = ruleContext.getArguments();
		var rulerunnerOutput = (RuleRunnerBean.RuleRunnerOutput) arguments.get("rulerunnerOutput");
		var paths = arguments.getString("paths");
		var unlimitedGlob = arguments.getBoolean("unlimitedGlob");
		var skip = arguments.getInt("skip") * 1048576L;
		var filterPattern = arguments.getString("filterPattern");
		var startPattern = arguments.getString("startPattern");
		var stopPattern = arguments.getString("stopPattern");
		var maxRecords = arguments.getInt("maxRecords");
		var parser = arguments.getString("parser");

		// prepare line selection
		var filter = ((filterPattern == null) || filterPattern.isEmpty()) ? null : Pattern.compile(filterPattern);
		var startFinder = ((startPattern == null) || startPattern.isEmpty()) ? null : Pattern.compile(startPattern);
		var stopFinder = ((stopPattern == null) || stopPattern.isEmpty()) ? null : Pattern.compile(stopPattern);

		// prepare files list
		var files = new ArrayList<String>();
		for (var path: paths.split("\n"))
		{
			if (path.startsWith("classpath/"))
			{
				files.add(path);
			}
			else
			{
				files.addAll(glob(path, unlimitedGlob));
			}
		}

		// check files list and determine input type
		var inputType = checkInputFiles(files);

		// prepare parsing
		if ((parser != null) && !parser.isEmpty())
		{
			inputType = parser;
		}
		var parserPattern = knownTypes.get(inputType);
		if ((parserPattern == null) && (parser != null) && !parser.isEmpty())
		{
			parserPattern = parser;
		}
		if (parserPattern == null)
		{
			throw new RuntimeException("Unknown file type: " + inputType);
		}
		var lineParser = Pattern.compile(parserPattern);

		// prepare named groups
		var groupMatcher = Pattern.compile("\\(\\?<([A-Za-z][A-Za-z0-9]*)>").matcher(parserPattern);
		var groupNames = new ArrayList<String>();
		while (groupMatcher.find())
		{
			groupNames.add(groupMatcher.group(1));
		}
		if (groupNames.isEmpty())
		{
			throw new RuntimeException("Parser does not define any named captures");
		}

		// build result iterator
		var records = new RecordsIterator(rulerunnerOutput, groupNames, lineParser, skip, filter, startFinder, stopFinder, maxRecords, files);

		// return the iterator when invoked from the Generic Query, otherwise write output
		if (rulerunnerOutput == null)
		{
			return records;
		}
		else
		{
			writeOutput(rulerunnerOutput, records, groupNames);
			return null;
		}
	}

	private static void writeOutput(RuleRunnerBean.RuleRunnerOutput rulerunnerOutput, RecordsIterator records, ArrayList<String> groupNames)
	{
		var writer = new TabularData(groupNames, false);
		writer.setOutputStream(rulerunnerOutput, "UTF-8");
		try
		{
			writer.writeHtmlHeader();
			while (records.hasNext())
			{
				writer.writeHtmlRecord(records.next());
			}
			writer.writeHtmlFooter();
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
		rulerunnerOutput.addMessage("%d rows in %.02f seconds, %d of %d lines examined, %d lines used", records.numRecords, rulerunnerOutput.getDuration(), records.linesExamined, records.lineCount, records.linesUsed);
	}

	@SuppressWarnings("unchecked")
	private static List<String> glob(String spec, boolean unlimitedGlob)
	{
		// prepare path component patterns, the first component is taken as root
		var components = spec.split("/", -1);
		var patterns = new Object[components.length - 1];
		for (var i = 1; i < components.length; ++i)
		{
			var component = components[i];
			if (component.contains("?") || component.contains("*"))
			{
				var buffer = new StringBuilder();
				var matcher = Pattern.compile("[?*]").matcher(component);
				var position = 0;
				while (true)
				{
					if (matcher.find(position))
					{
						if (matcher.start() > position)
						{
							buffer.append(Pattern.quote(component.substring(position, matcher.start())));
						}
						buffer.append(matcher.group().equals("?") ? "." : ".*" );
						position = matcher.end();
					}
					else {
						if (position < component.length() - 1)
						{
							buffer.append(Pattern.quote(component.substring(position)));
						}
						break;
					}
				}
				patterns[i - 1] = Pattern.compile(buffer.toString());
			}
			else {
				patterns[i - 1] = component;
			}
		}
		// walk tree
		var result = new ArrayList<String>();
		var roots = new File[patterns.length];
		var iterators = new Iterator[patterns.length];
		roots[0] = new File(components[0] + "/");
		iterators[0] = null;
		var level = 0;
		while (level >= 0)
		{
			var dir = roots[level];
			var iterator = iterators[level];
			if (iterator == null) {
				var pattern =  patterns[level];
				var entries = new ArrayList<String>();
				if (pattern instanceof Pattern)
				{
					var dirlist = dir.list();
					if (dirlist != null)
					{
						for (var entry: dirlist)
						{
							if (((Pattern) pattern).matcher(entry).matches())
							{
								entries.add(entry);
							}
						}
					}
				}
				else
				{
					entries.add((String) pattern);
				}
				iterators[level] = entries.iterator();
			}
			else {
				if (iterator.hasNext())
				{
					var path = new File(dir, ((Iterator<String>) iterator).next());
					if (level < patterns.length - 1)
					{
						if (path.isDirectory())
						{
							++level;
							roots[level] = path;
							iterators[level] = null;
						}
					}
					else
					{
						if (!path.isFile())
						{
							throw new RuntimeException("Not a file: " + path);
						}
						result.add(path.toString());
					}
				}
				else {
					--level;
				}
			}
		}
		if (!unlimitedGlob && (result.size() > 100))
		{
			throw new RuntimeException("Too many matches for " + spec);
		}
		return result;
	}

	private static String checkInputFiles(List<String> files)
	{
		String inputType = null;
		for (var path : files)
		{
			// the first file determines the input type if not specified
			if (inputType == null)
			{
				inputType = path.replaceAll("[^/]*/", "").replaceAll("[-.][-0-9]+", "").replaceAll("\\.gz$", "");
			}
			var inputStream = openInputStream(path);
			try
			{
				inputStream.close();
			}
			catch (IOException e)
			{
				// safe close
			}
		}
		return inputType;
	}

	private static InputStream openInputStream(String path)
	{
		try
		{
			var inputStream = path.startsWith("classpath/") ? Identity.class.getClassLoader().getResourceAsStream(path.substring(10)) : new FileInputStream(path);
			if (inputStream == null)
			{
				throw new RuntimeException("Resource not found: " + path);
			}
			return inputStream;
		}
		catch (IOException e)
		{
			throw new RuntimeException(e.getMessage());
		}
	}
}
