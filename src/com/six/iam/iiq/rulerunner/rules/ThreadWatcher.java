package com.six.iam.iiq.rulerunner.rules;

import com.six.iam.iiq.rulerunner.RuleRunnerBean;
import com.six.iam.util.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.Map;
import java.util.regex.Pattern;
import sailpoint.api.SailPointContext;
import sailpoint.object.*;
import sailpoint.tools.GeneralException;

public class ThreadWatcher implements JavaRuleExecutor
{
	@Override
	public Object execute(JavaRuleContext ruleContext) throws Exception
	{
		var context = ruleContext.getContext();
		var arguments = ruleContext.getArguments();
		var rulerunnerOutput = (RuleRunnerBean.RuleRunnerOutput) arguments.get("rulerunnerOutput");
		var threadSelectorPatterns = arguments.getString("threadSelectorPatterns");
		var sampleInterval = arguments.getInt("sampleInterval");
		var sampleCount = arguments.getInt("sampleCount");
		var aggregationPattern = arguments.getString("aggregationPattern");
		var suspendPattern = arguments.getString("suspendPattern");
		var resumePattern = arguments.getString("resumePattern");
		var runTop = arguments.getBoolean("runTop");
		var runJmap = arguments.getBoolean("runJmap");

		// prepare parameters
		Pattern threadNameSelector = null;
		Pattern threadFrameSelector = null;
		if ((threadSelectorPatterns != null) && !threadSelectorPatterns.trim().isEmpty())
		{
			var patterns = threadSelectorPatterns.trim().split(" +", -1);
			threadNameSelector = Pattern.compile(patterns[0]);
			if (patterns.length > 1)
			{
				threadFrameSelector = Pattern.compile(patterns[1]);
			}
			if (patterns.length > 2)
			{
				throw new RuntimeException("Invalid thread selector");
			}
		}
		int intervalMillis = (sampleInterval > 0) ? sampleInterval : 20;
		int numSamples = (sampleCount > 0) ? sampleCount : 100;
		Pattern aggregationSelector = ((aggregationPattern != null) && !aggregationPattern.trim().isEmpty()) ? Pattern.compile(aggregationPattern) : null;
		Pattern suspendSelector = ((suspendPattern != null) && !suspendPattern.trim().isEmpty()) ? Pattern.compile(suspendPattern) : null;
		Pattern resumeSelector = ((resumePattern != null) && !resumePattern.trim().isEmpty()) ? Pattern.compile(resumePattern) : null;

		// select threads
		var threads = new HashSet<Thread>();
		selectThreads(threadNameSelector, threadFrameSelector, threads, numSamples, intervalMillis);
		if (threads.isEmpty())
		{
			rulerunnerOutput.addMessage("No threads selected");
			numSamples = 0;
		}

		// collect and analyze stacktraces
		var all = new HashMap<String, Object>();
		var summary = new TreeMap<String, Object>();
		var states = new TreeMap<String, Object>();
		var matrix = new HashMap<String, Object>();
		var details = new TreeMap<String, Object>();
		var aggregations = (aggregationSelector != null) ? new TreeMap<String, Object>() : null;
		var start = new Date();
		doSampling(threads, suspendSelector, resumeSelector, aggregationSelector, all, aggregations, details, states, summary, matrix, numSamples, intervalMillis);
		var stop = new Date();

		// write output
		writeSummary(rulerunnerOutput, context, numSamples, all);
		if (numSamples > 0)
		{
			writeSamplingResults(rulerunnerOutput, numSamples, stop, start, threads, states, summary, matrix, aggregations, details);
		}
		if (runTop)
		{
			runCommand(rulerunnerOutput, "top", new ProcessBuilder("/bin/top", "-Hbn1", "-p", String.format("%d", ProcessHandle.current().pid())));
		}
		if (runJmap)
		{
			runCommand(rulerunnerOutput, "jmap", new ProcessBuilder("/usr/bin/jmap", "-histo:live", String.format("%d", ProcessHandle.current().pid())));
		}

		return null;
	}

	private static void selectThreads(Pattern threadNameSelector, Pattern threadFrameSelector, HashSet<Thread> threads, int numSamples, int intervalMillis) throws InterruptedException
	{
		while (numSamples > 0)
		{
			for (var candidate: Thread.getAllStackTraces().entrySet())
			{
				if (candidate.getKey() == Thread.currentThread())
				{
					continue;
				}
				if ((threadNameSelector != null) && !threadNameSelector.matcher(candidate.getKey().getName()).find())
				{
					continue;
				}
				if (threadFrameSelector != null)
				{
					var match = false;
					for (var stackFrame: candidate.getValue())
					{
						if (threadFrameSelector.matcher(String.format("%s:%s() [%s:%d]", stackFrame.getClassName(), stackFrame.getMethodName(), stackFrame.getFileName(), stackFrame.getLineNumber())).find())
						{
							match = true;
							break;
						}
					}
					if (!match)
					{
						continue;
					}
				}
				threads.add(candidate.getKey());
			}
			if (!threads.isEmpty())
			{
				break;
			}
			--numSamples;
			Thread.sleep(intervalMillis);
		}
	}

	private static void doSampling(HashSet<Thread> threads, Pattern suspendSelector, Pattern resumeSelector, Pattern aggregationSelector, HashMap<String, Object> all, TreeMap<String, Object> aggregations, TreeMap<String, Object> details, TreeMap<String, Object> states, TreeMap<String, Object> summary, HashMap<String, Object> matrix, int numSamples, int intervalMillis) throws InterruptedException
	{
		while (numSamples > 0)
		{
			for (var thread: Thread.getAllStackTraces().entrySet())
			{
				var threadState = thread.getKey().getState().toString();
				if (thread.getKey() != Thread.currentThread())
				{
					IndexedData.count(all, threadState);
					IndexedData.count(all, "*");
				}
				if (threads.contains(thread.getKey()))
				{
					var threadName = thread.getKey().getName();
					var aggregationsSeen = new HashSet<String>();
					var keyChain = new ArrayList<String>();
					keyChain.add(String.format("[%s]", threadName));
					String label = null;
					var suspending = -1;
					var stackFrames = thread.getValue();
					for (var i = stackFrames.length; --i >= 0;)
					{
						var stackFrame = stackFrames[i];
						label = String.format("%s:%s() [%s:%d]", stackFrame.getClassName(), stackFrame.getMethodName(), stackFrame.getFileName(), stackFrame.getLineNumber());
						if (suspending >= 0)
						{
							if ((resumeSelector != null) && resumeSelector.matcher(label).find())
							{
								var skipped = suspending - i;
								if (skipped > 0)
								{
									keyChain.add(String.format("... %d skipped ...", skipped));
								}
								keyChain.add(label);
								suspending = suspendSelector.matcher(label).find() ? i - 1 : -1;
							}
						}
						else
						{
							keyChain.add(label);
							if ((suspendSelector != null) && suspendSelector.matcher(label).find())
							{
								suspending = i - 1;
							}
						}
						if ((aggregationSelector != null) && aggregationSelector.matcher(label).find())
						{
							var aggregationLabel = label;
							while (aggregationsSeen.contains(aggregationLabel))
							{
								aggregationLabel = aggregationLabel + " *";
							}
							IndexedData.count(aggregations, aggregationLabel, threadState);
							aggregationsSeen.add(aggregationLabel);
						}
					}
					keyChain.add(threadState);
					String[] keyArray = new String[keyChain.size()];
					keyChain.toArray(keyArray);
					IndexedData.count(details, keyArray);
					IndexedData.count(states, threadName, threadState);
					if (label != null)
					{
						IndexedData.count(summary, label, threadState);
						IndexedData.count(matrix, label, threadName, threadState);
					}
				}
			}
			--numSamples;
			Thread.sleep(intervalMillis);
		}
	}

	private static void writeSummary(RuleRunnerBean.RuleRunnerOutput rulerunnerOutput, SailPointContext context, int numSamples, HashMap<String, Object> all) throws IOException, GeneralException
	{
		rulerunnerOutput.printNoEscape("<h3>Overview</h3>\n");
		var runtime = Runtime.getRuntime();
		var runtimeBean = ManagementFactory.getRuntimeMXBean();
		var osBean = ManagementFactory.getOperatingSystemMXBean();
		var processInfo = new HashMap<String, Object>();
		processInfo.put("JVM started", new Date(runtimeBean.getStartTime()));
		processInfo.put("PID", ProcessHandle.current().pid());
		processInfo.put("Host", System.getProperty("host.name"));
		processInfo.put("Max MiB", String.format("%.0f", runtime.maxMemory() / 1048576.));
		processInfo.put("Used MiB", String.format("%.0f", (runtime.totalMemory() - runtime.freeMemory()) / 1048576.));
		processInfo.put("Available MiB", String.format("%.0f", (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / 1048576.));
		// what a mess what is necessary to call a method the bean provides and avoid 'class com.six.iam.iiq.rulerunner.rules.ThreadWatcher cannot access class com.sun.management.internal.OperatingSystemImpl (in module jdk.management) because module jdk.management does not export com.sun.management.internal to unnamed module'
		processInfo.put("CPU %", context.runScript(new Script("String.format(\"%.0f\", osBean.getProcessCpuLoad() * osBean.getAvailableProcessors() * 100)"), Literals.asMap("osBean", osBean)));
		if (numSamples > 0)
		{
			processInfo.put("RUNNABLE %", (Integer) all.get("RUNNABLE") * 100 / numSamples);
			processInfo.put("Threads", (Integer) all.get("*") / numSamples);
		}
		var processInfoWriter = new TabularData(List.of("JVM started", "PID", "Host", "Max MiB", "Used MiB", "Available MiB", "CPU %", "RUNNABLE %", "Threads"), false);
		processInfoWriter.setOutputStream(rulerunnerOutput, "UTF-8");
		processInfoWriter.writeHtmlHeader();
		processInfoWriter.writeHtmlRecord(processInfo);
		processInfoWriter.writeHtmlFooter();
	}

	@SuppressWarnings("unchecked")
	private static void writeSamplingResults(RuleRunnerBean.RuleRunnerOutput rulerunnerOutput, int numSamples, Date stop, Date start, HashSet<Thread> threads, TreeMap<String, Object> states, TreeMap<String, Object> summary, HashMap<String, Object> matrix, TreeMap<String, Object> aggregations, TreeMap<String, Object> details) throws IOException
	{
		rulerunnerOutput.printNoEscape("<br>");
		rulerunnerOutput.printNoEscape(
				"<p>%d samples taken in %.2f seconds watching %d threads (capture interval: %tT - %tT)</p>\n", numSamples, (stop.getTime() - start.getTime())/1000., threads.size(), start, stop
		);
		IndexedData.writeHtmlMatrix(states, rulerunnerOutput, "UTF-8", null, true, false);

		rulerunnerOutput.printNoEscape("<h3>Methods executing at sample time</h3>\n");
		IndexedData.writeHtmlMatrix(summary, rulerunnerOutput, "UTF-8", null, true, false);

		var persistingStates = new TreeMap<String, Object>();
		for (var methodEntry: matrix.entrySet())
		{
			for (var threadEntry: ((Map<String, Object>) methodEntry.getValue()).entrySet())
			{
				for (var stateEntry: ((Map<String, Object>) threadEntry.getValue()).entrySet())
				{
					if ((Integer) stateEntry.getValue() == numSamples)
					{
						IndexedData.put(persistingStates, stateEntry.getKey().substring(0, 1), methodEntry.getKey(), threadEntry.getKey());
					}
				}
			}
		}
		if (!persistingStates.isEmpty())
		{
			rulerunnerOutput.printNoEscape("<h3>Persisting thread states</h3>\n");
			IndexedData.writeHtmlMatrix(persistingStates, rulerunnerOutput, "UTF-8", null, false, true);
		}

		if (aggregations != null)
		{
			rulerunnerOutput.printNoEscape("<h3>Aggregated stack frame counts</h3>\n");
			IndexedData.writeHtmlMatrix(aggregations, rulerunnerOutput, "UTF-8", null, true, false);
		}

		rulerunnerOutput.printNoEscape("<h3>Sample details</h3>\n");
		rulerunnerOutput.printNoEscape("<pre>");
		for (var detail: details.entrySet())
		{
			rulerunnerOutput.print("%s = ", detail.getKey());
			rulerunnerOutput.print(IndexedData.pprint(IndexedData.compress((Map<String, Object>) detail.getValue(), "\n"), -1, true));
			rulerunnerOutput.print("\n");
		}
		rulerunnerOutput.printNoEscape("</pre>");
	}

	private static void runCommand(RuleRunnerBean.RuleRunnerOutput rulerunnerOutput, String heading, ProcessBuilder command) throws IOException
	{
		rulerunnerOutput.printNoEscape(String.format("<h3>%s</h3>\n", heading));
		rulerunnerOutput.printNoEscape("<pre>");
		try
		{
			var process = command.start();
			var commandOutput = new ByteArrayOutputStream();
			process.getInputStream().transferTo(commandOutput);
			rulerunnerOutput.print(commandOutput.toString());
		}
		finally
		{
			rulerunnerOutput.printNoEscape("</pre>");
		}
	}
}
