package com.six.iam.iiq.rulerunner;

import com.six.iam.util.CerberusLogic;
import com.six.iam.util.OrionQL;
import org.apache.commons.lang.StringEscapeUtils;
import javax.faces.context.FacesContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Pattern;

import sailpoint.api.*;
import sailpoint.object.*;
import sailpoint.tools.*;
import sailpoint.tools.xml.XMLObjectFactory;
import sailpoint.web.BaseBean;

public class RuleRunnerBean extends BaseBean
{
	private static final String DEFAULT_JAVA_NATIVE_METHOD = "execute";
	private static final String BACKGROUND_EXECUTOR_TASK = "SIX RuleRunnerBackgroundExecutor";
	// this must match the IIQ builtin limit for parallel task executions to inform the user about queueing
	private static final int MAX_BACKGROUND_TASKS = 4;

	public static class Argument
	{
		private final String name;
		private final String label;
		private final String helpText;
		private final String type;
		private final List<String[]> choices;
		private Object value;

		Argument(sailpoint.object.Argument definition)
		{
			name = definition.getName();
			label = definition.getPrompt();
			helpText = definition.getDescription();
			type = definition.getType().startsWith("choice:") ? "choice" : definition.getType();
			if ("choice".equals(type))
			{
				choices = new ArrayList<>();
				var parser = Pattern.compile("([^=]*)(=.*)?");
				for (var choice: definition.getType().substring(7).split(",", -1))
				{
					var matcher = parser.matcher(choice);
					if (!matcher.matches())
					{
						throw new RuntimeException("Invalid choice: " + choice);
					}
					choices.add(new String[]{
							matcher.group(1),
							(matcher.group(2) == null) ? matcher.group(1) : matcher.group(2).substring(1)
					});
				}
			}
			else
			{
				choices = null;
			}
		}

		private void initialize(Map<String, String[]> requestParameters)
		{
			var input = requestParameters.get(name);
			if ((input != null) && (input.length == 1))
			{
				value = type.equals("boolean") ? "on".equals(input[0]) : input[0];
			}
		}

		public static Map<String, Object> getMap(List<Argument> arguments)
		{
			var result = new HashMap<String, Object>();
			for (var argument: arguments)
			{
				var value = argument.value;
				if (value instanceof String)
				{
					var valueString = (String) value;
					if ("int".equals(argument.type))
					{
						value = valueString.isEmpty() ? null : Integer.valueOf(valueString);
					}
					else if ("date".equals(argument.type))
					{
						if (valueString.isEmpty())
						{
							value = null;
						}
						else
						{
							var parser = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS".substring(0, Math.min(valueString.length(), 19)));
							value = parser.parse(valueString, new ParsePosition(0));
							if ((value == null) || !valueString.equals(parser.format(value)))
							{
								throw new RuntimeException("Invalid date: " + valueString);
							}
						}
					}
					else if ("boolean".equals(argument.type))
					{
						value = !valueString.isEmpty() && Boolean.parseBoolean(valueString);
					}
				}
				result.put(argument.name, value);
			}
			return result;
		}

		public String getName()
		{
			return name;
		}

		public String getLabel()
		{
			return label;
		}

		@SuppressWarnings("unused")
		public String getHelpText()
		{
			return helpText;
		}

		public String getType()
		{
			return type;
		}

		public Object getValue()
		{
			return value;
		}

		public void setValue(Object value)
		{
			this.value = value;
		}

		@SuppressWarnings("unused")
		public List<String[]> getChoices()
		{
			return choices;
		}
	}

	public static class RuleRunnerOutput extends ByteArrayOutputStream
	{
		private static final long MAX_FOREGROUND_TIME_MILLIS = 40000L;
		private static final long EXTEND_FOREGROUND_TIME_MILLIS = 10000L;
		private static final int FOREGROUND_SIZE_LIMIT = 16*1024*1024;
		private static final int BACKGROUND_SIZE_LIMIT = 256*1024*1024;

		private boolean logExceptionStacktrace = false;
		private final String title;
		private final String link;
		private final String webroot;
		private String mimeType = "text/html";
		private final int maxOutputSize;
		private final List<String> messages = new ArrayList<>();
		private final List<Message> taskMessages;
		private int outputStart = 0;
		private long start = System.currentTimeMillis();
		private boolean reportDuration = true;
		private long stop = Long.MAX_VALUE;
		private boolean isExecutionTimeExtended = false;
		private boolean shouldTerminate = false;
		private boolean isTerminated = false;

		public RuleRunnerOutput(String title, String link, String webroot, List<Message> taskMessages) throws IOException
		{
			this.title = StringEscapeUtils.escapeHtml(title);
			this.link = link;
			this.webroot = webroot;
			this.taskMessages = taskMessages;
			this.maxOutputSize = (taskMessages == null) ? FOREGROUND_SIZE_LIMIT : BACKGROUND_SIZE_LIMIT;
			if (webroot != null)
			{
				// create standalone HTML document with all styles inlined
				super.write(String.format("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\"><title>%s</title><style>", title).getBytes(StandardCharsets.UTF_8));
				for (var style: List.of("global.css", "rulerunner.css"))
				{
					try (var rulerunnerStyles = new FileInputStream(new File(webroot, new File("rulerunner", style).getPath())))
					{
						rulerunnerStyles.transferTo(this);
					}
				}
				super.write("</style>".getBytes(StandardCharsets.UTF_8));
				super.write(String.format("</head><body><h1>%s</h1><div class=\"rulerunnerOutput\">", title).getBytes(StandardCharsets.UTF_8));
				outputStart = size();
			}
		}

		public void setLogExceptionStacktrace()
		{
			logExceptionStacktrace = true;
		}

		public void finishOutput() throws IOException
		{
			if ("text/html".equals(mimeType))
			{
				// note that this message will intentionally not go into taskMessages
				messages.add(String.format(
						"<div>%tF %1$tT%s</div>",
						new Date(),
						(link == null) ?
								"" :
								String.format(
										" <a href=\"%s\" target=\"_blank\">%s</a>%s",
										link,
										title,
										reportDuration ? String.format(" (%.03f s)", getDuration()) : ""
								)
				));
				if (size() > outputStart)
				{
					write("<br>".getBytes(StandardCharsets.UTF_8));
				}
				for (var message: messages)
				{
					write(message.getBytes(StandardCharsets.UTF_8));
				}
				if (webroot != null)
				{
					write("</div></body></html>".getBytes(StandardCharsets.UTF_8));
				}
			}
		}

		/**
		 * <p>Sets the mime type of the output (Default: text/html). This needs to be done before
		 * doing any output.
		 * </p>
		 * <p>The mime type is used when the output needs to be converted into a file (for download or
		 * sending as mail attachment) and to control print() HTML escaping.
		 * </p>
		 * @param mimeType mime type to set
		 */
		@SuppressWarnings("unused")
		public void setMimeType(String mimeType)
		{
			this.mimeType = mimeType;
			if (!"text/html".equals(mimeType) && (webroot != null))
			{
				reset();
				outputStart = 0;
			}
		}

		public String getMimeType()
		{
			return mimeType;
		}

		public EmailFileAttachment.MimeType getAttachmentMimeType()
		{
			for (var candidate: EmailFileAttachment.MimeType.values())
			{
				if (mimeType.equals(candidate.getType()))
				{
					return candidate;
				}
			}
			return null;
		}

		public String getOutputFileName()
		{
			String extension = mimeType.substring(mimeType.indexOf('/') + 1);
			if ("plain".equals(extension))
			{
				extension = "txt";
			}
			// use only characters allowed in filenames
			var timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
			return String.format("%s %s.%s", title, timestamp, extension);
		}

		private String format(String text, Object... args)
		{
			return (args.length == 0) ? text : String.format(text, args);
		}

		/**
		 * <p>Writes <tt>text</tt> to the output with correct HTML escaping, optionally formatting-in the supplied args (see the JDK {@link String#format(String, Object...)} method).
		 * </p>
		 * <p>Note that no newline is implicitly written, similar to the behavior of java.io.PrintStream.
		 * </p>
		 * @param text text to write or formatting template if args are given
		 * @param args values to format into <tt>text</tt>
		 */
		@SuppressWarnings("unused")
		public void print(String text, Object... args) throws IOException
		{
			String messageText = format(text, args);
			if ("text/html".equals(mimeType))
			{
				messageText = StringEscapeUtils.escapeHtml(messageText);
			}
			write(messageText.getBytes(StandardCharsets.UTF_8));
		}

		/**
		 * Variant of {@link #print(String, Object...)} that does <i>no</i> HTML escaping and therefore can be used to write HTML constructs.
		 * @param text text to write or formatting template if args are given
		 * @param args values to format into <tt>message</tt>
		 */
		@SuppressWarnings("unused")
		public void printNoEscape(String text, Object... args) throws IOException
		{
			write(format(text, args).getBytes(StandardCharsets.UTF_8));
		}

		/**
		 * Add <tt>message</tt> to the output, optionally formatting-in the supplied args (see the JDK {@link String#format(String, Object...)} method).
		 * @param message message to add or formatting template if args are given
		 * @param args values to format into <tt>message</tt>
		 */
		@SuppressWarnings("unused")
		public void addMessage(String message, Object... args)
		{
			String messageText = format(message, args);
			messages.add(String.format("<div>%s</div>", StringEscapeUtils.escapeHtml(messageText)));
			if (taskMessages != null)
			{
				taskMessages.add(Message.info(messageText));
			}
		}

		/**
		 * Add <tt>message</tt> to the output with class <i>error</i>, optionally formatting-in the supplied args (see the JDK {@link String#format(String, Object...)} method).
		 * @param message message to add or formatting template if args are given
		 * @param args values to format into <tt>message</tt>
		 */
		@SuppressWarnings("unused")
		public void addError(String message, Object... args)
		{
			if ((args.length > 0) && (args[args.length - 1] instanceof Throwable))
			{
				var buffer = new StringWriter();
				((Throwable) args[args.length - 1]).printStackTrace(new PrintWriter(buffer));
				args[args.length - 1] = buffer.toString();
			}
			String messageText = format(message, args);
			messages.add(String.format("<div class=\"error\">%s</div>", StringEscapeUtils.escapeHtml(messageText)));
			if (taskMessages != null)
			{
				taskMessages.add(Message.error(messageText));
			}
		}

		/**
		 * Convenience method to log an exception as error message
		 * @param exception exception to log
		 */
		public void addError(Throwable exception)
		{
			if (logExceptionStacktrace)
			{
				var buffer = new StringWriter();
				exception.printStackTrace(new PrintWriter(buffer));
				addError(buffer.toString());
			}
			else
			{
				addError(exception.getMessage());
			}
		}

		/**
		 * Convenience method that invokes either {@link #addMessage(String, Object...)} or {@link #addError(String, Object...)} depending on the value of the first parameter
		 * @param isError if <tt>true</tt>, message will be rendered with class <i>error</i>
		 * @param message message to add or formatting template if args are given
		 * @param args values to format into <tt>message</tt>
		 */
		@SuppressWarnings("unused")
		public void addMessage(boolean isError, String message, Object... args)
		{
			if (isError)
			{
				addError(message, args);
			}
			else
			{
				addMessage(message, args);
			}
		}

		public String getMessages()
		{
			return String.join("\n", messages);
		}

		/**
		 * Reset the duration stopwatch and disable the default duration message
		 */
		public void startTimeMonitoring()
		{
			var delay = System.currentTimeMillis() - start;
			if (reportDuration && (delay > 1000L))
			{
				addMessage("Rule start was delayed by %.02f seconds", delay / 1000.);
			}
			reportDuration = false;
			start = System.currentTimeMillis();
			if (taskMessages == null)
			{
				stop = start + MAX_FOREGROUND_TIME_MILLIS;
			}
		}

		public void requestTermination()
		{
			shouldTerminate = true;
		}

		public boolean isTerminated()
		{
			return isTerminated;
		}

		/**
		 * Check if the rule should terminate due to the time limit or output size limit having been reached, or as requested by user
		 * @param allowTimeExtension If the time limit was reached, a 10 seconds grace period can be
		 *                           requested once by setting this parameter to <tt>true</tt>. If
		 *                           granted, the method will return <tt>false</tt>.
		 * @return true if rule should terminate
		 */
		public boolean isTerminationRequested(boolean allowTimeExtension)
		{
			if (shouldTerminate)
			{
				addMessage("Terminated by user request");
				isTerminated = true;
				return true;
			}
			if (size() > maxOutputSize)
			{
				addMessage("Maximum output size was reached");
				return true;
			}
			if (System.currentTimeMillis() > stop)
			{
				if (!isExecutionTimeExtended && allowTimeExtension)
				{
					addMessage("Maximum execution time was extended after %d seconds", (System.currentTimeMillis() - start) / 1000L);
					stop = System.currentTimeMillis() + EXTEND_FOREGROUND_TIME_MILLIS;
					isExecutionTimeExtended = true;
				}
				else
				{
					addMessage("Maximum execution time was reached");
					return true;
				}
			}
			return false;
		}

		/**
		 * Return the measured execution time in seconds and disable the default duration message
		 * @return elapsed time in seconds
		 */
		public double getDuration()
		{
			reportDuration = false;
			return (System.currentTimeMillis() - start) / 1000.;
		}
	}

	private String ruleName = null;
	private Rule rule = null;
	private String title = null;
	private Boolean runInBackground = null;
	private Boolean runImmediate = null;
	private List<Argument> arguments = null;
	private String link = null;
	private String baseurl = null;

	public RuleRunnerBean() throws GeneralException, IOException
	{
		var externalContext = FacesContext.getCurrentInstance().getExternalContext();
		var request = (HttpServletRequest) externalContext.getRequest();
		var parameterMap = request.getParameterMap();
		for (var requestParameter: parameterMap.entrySet())
		{
			switch (requestParameter.getKey())
			{
				case "rule":
				case "argumentsForm:rule":
					setRule(requestParameter.getValue()[0]);
					break;
				case "title":
				case "argumentsForm:title":
					title = requestParameter.getValue()[0];
					break;
				case "runInBackground":
				case "argumentsForm:runInBackground":
					runInBackground = "on".equals(requestParameter.getValue()[0]);
					break;
				case "runImmediate":
					if (runImmediate == null)
					{
						runImmediate = "on".equals(requestParameter.getValue()[0]);
					}
					break;
			}
		}
		if (arguments != null)
		{
			for (var argument: arguments)
			{
				argument.initialize(parameterMap);
			}
		}
	}

	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	public static boolean isAuthorized(Identity identity, Rule rule, SailPointContext context) throws GeneralException
	{
		if (identity == null)
		{
			return false;
		}
		var identityCapabilities = identity.getCapabilityManager().getEffectiveCapabilities();
		if (Capability.hasSystemAdministrator(identityCapabilities))
		{
			return true;
		}
		if (rule == null)
		{
			return false;
		}
		var ruleAttributes = rule.getAttributes();
		if (ruleAttributes == null)
		{
			return false;
		}
		var authorizingCapabilities = ruleAttributes.getStringList("authorizingCapabilities");
		if (authorizingCapabilities != null)
		{
			for (var capability: authorizingCapabilities)
			{
				if (Capability.hasCapability(capability, identityCapabilities))
				{
					return true;
				}
			}
		}
		var authorizingDynamicScopes = ruleAttributes.getStringList("authorizingDynamicScopes");
		if (authorizingDynamicScopes != null)
		{
			var matchmaker = new DynamicScopeMatchmaker(context);
			for (var scopeName: authorizingDynamicScopes)
			{
				if (matchmaker.isMatch(context.getObjectByName(DynamicScope.class, scopeName), identity, false))
				{
					return true;
				}
			}
		}
		return false;
	}

	public String getRule()
	{
		return (rule == null) ? null : ruleName;
	}

	public void setRule(String ruleName) throws GeneralException, IOException
	{
		this.ruleName = ruleName;
		rule = loadRule(getContext(), ruleName);
		if (!isAuthorized(getLoggedInUser(), rule, getContext()))
		{
			var externalContext = FacesContext.getCurrentInstance().getExternalContext();
			externalContext.redirect("/identityiq/accessDenied.jsf");
		}
		if (rule != null)
		{
			arguments = parseRuleSignature(rule);
			var attributes = rule.getAttributes();
			if (attributes != null)
			{
				if (title == null)
				{
					title = attributes.getString("title");
				}
				if (runInBackground == null)
				{
					runInBackground = attributes.getBoolean("runInBackground");
				}
				if (attributes.containsKey("runImmediate"))
				{
					runImmediate = attributes.getBoolean("runImmediate");
				}
			}
		}
	}

	public String getTitle()
	{
		if (rule == null)
		{
			return "Error";
		}
		return ((title == null) || title.isEmpty()) ? ruleName : title;
	}

	@SuppressWarnings("unused")
	public boolean getRunImmediate()
	{
		return isProxyRule(rule) && Boolean.TRUE.equals(runImmediate);
	}

	public List<Argument> getArguments()
	{
		return arguments;
	}

	public String getLink()
	{
		return link;
	}

	public void setLink(String link)
	{
		this.link = link;
		if (link != null)
		{
			var matcher = Pattern.compile("^https?://[^?]+/").matcher(link);
			if (matcher.find())
			{
				baseurl = matcher.group();
			}
		}
	}

	public String getOutput() throws IOException
	{
		return executeRule(false);
	}

	public String getDownload() throws IOException
	{
		return executeRule(true);
	}

	private String executeRule(boolean isDownload) throws IOException
	{
		if (rule == null)
		{
			return "<div class=\"error\">No such rule</div>";
		}
		var facesContext = FacesContext.getCurrentInstance();
		if (!facesContext.isPostback())
		{
			return null;
		}
		var rulerunnerOutput = new RuleRunnerBean.RuleRunnerOutput(getTitle(), link, isDownload ? facesContext.getExternalContext().getRealPath("/") : null, null);
		if (!isProxyRule(rule))
		{
			rulerunnerOutput.setLogExceptionStacktrace();
		}
		try
		{
			var argumentsMap = Argument.getMap(this.arguments);
			if (Boolean.TRUE.equals(runInBackground))
			{
				var email = getLoggedInUser().getEmail();
				if (email == null)
				{
					rulerunnerOutput.addError("You need an email address to receive the background execution results");
				}
				else
				{
					if (getContext().countObjects(TaskResult.class, new QueryOptions(
							Filter.eq("definition.name", BACKGROUND_EXECUTOR_TASK),
							Filter.isnull("completed")
					)) >= MAX_BACKGROUND_TASKS)
					{
						rulerunnerOutput.addMessage("The maximum allowed number of background jobs is already active. Your request will be queued");
					}
					var taskParameters = new Attributes<String, Object>();
					taskParameters.put("ruleName", rule.getName());
					taskParameters.put("title", getTitle());
					taskParameters.put("link", link);
					taskParameters.put("baseurl", baseurl);
					taskParameters.put("email", email);
					taskParameters.put("arguments", XMLObjectFactory.getInstance().toXmlNoIndent(argumentsMap));
					taskParameters.put("webroot", facesContext.getExternalContext().getRealPath("/"));
					taskParameters.put("resultName", String.format("%s %tF %2$tT", BACKGROUND_EXECUTOR_TASK, new Date()));
					new TaskManager(getContext()).run(BACKGROUND_EXECUTOR_TASK, taskParameters);
					rulerunnerOutput.addMessage("Request submitted, result will be sent to %s", email);
				}
			}
			else
			{
				runRule(getContext(), rule, argumentsMap, baseurl, rulerunnerOutput);
			}
		}
		catch (Exception e)
		{
			rulerunnerOutput.addError(e);
		}
		rulerunnerOutput.finishOutput();
		if (isDownload)
		{
			var data = rulerunnerOutput.toByteArray();
			var response = (HttpServletResponse) facesContext.getExternalContext().getResponse();
			response.setContentType(rulerunnerOutput.getMimeType());
			response.setHeader("Content-Disposition", String.format("attachment;filename=\"%s\"", rulerunnerOutput.getOutputFileName()));
			response.setContentLength(data.length);
			response.getOutputStream().write(data);
			facesContext.responseComplete();
			return null;
		}
		if (rulerunnerOutput.size() == 0)
		{
			return null;
		}
		// JSF chokes on null characters - replace them silently as we don't have a good way here anymore to add another message
		var result = rulerunnerOutput.toString();
		if (result.indexOf('\u0000') >= 0)
		{
			result = result.replace("\u0000", "[NUL]");
		}
		return result;
	}

	public static Rule loadRule(SailPointContext context, String ruleName) throws GeneralException
	{
		var dotPosition = ruleName.indexOf('.');
		if (dotPosition < 0)
		{
			var rule = context.getObject(Rule.class, ruleName);
			if (rule != null)
			{
				return rule;
			}
		}
		var library = context.getObject(Configuration.class, "RulerunnerLibrary" + ((dotPosition < 0) ? "" : "." + ruleName.substring(0, dotPosition)));
		if (library == null)
		{
			return null;
		}
		var rule = library.get(ruleName.substring(dotPosition + 1));
		return (rule instanceof Rule) ? (Rule) rule : null;
	}

	private static List<Argument> parseRuleSignature(Rule rule)
	{
		var arguments = new ArrayList<Argument>();
		var signature = rule.getSignature();
		if (signature != null)
		{
			for (var argument : signature.getArguments())
			{
				arguments.add(new Argument(argument));
			}
		}
		return arguments;
	}

	public static boolean isProxyRule(Rule rule)
	{
		return (rule != null) && "proxy".equals(rule.getLanguage());
	}

	public static Object runRule(SailPointContext context, Rule rule, Map<String, Object> argumentsMap, String baseurl, RuleRunnerOutput rulerunnerOutput) throws GeneralException
	{
		var environment = new HashMap<String, Object>();
		environment.put("baseurl", baseurl);
		environment.put("hostname", Util.getHostName());
		environment.put("username", context.getUserName());
		while (isProxyRule(rule))
		{
			var targetRuleName = rule.getAttributeValue("targetRuleName");
			var targetRule = (targetRuleName == null) ? null : loadRule(context, targetRuleName.toString());
			if (targetRule == null)
			{
				throw new RuntimeException("Invalid target rule reference in " + rule.getName());
			}
			var targetArguments = parseRuleSignature(targetRule);
			var targetArgumentValues = computeTargetArguments(rule, argumentsMap, environment);
			for (var argument: targetArguments)
			{
				var argumentValue = targetArgumentValues.get(argument.getName());
				if (argumentValue != null)
				{
					argument.setValue(argumentValue);
				}
			}
			environment.putAll(argumentsMap);
			argumentsMap = Argument.getMap(targetArguments);
			rule = targetRule;
		}
		argumentsMap.put("rulerunnerOutput", rulerunnerOutput);
		argumentsMap.put("rulerunnerEnvironment", environment);
		if ("javanative".equals(rule.getLanguage()))
		{
			var matcher = Pattern.compile("([^;]+);?(.+)?").matcher(rule.getSource());
			if (!matcher.matches())
			{
				throw new GeneralException("Invalid javanative specification in rule " + rule.getName());
			}
			try
			{
				var ruleClass = Class.forName(matcher.group(1));
				var ruleExecutor = ruleClass.getConstructor().newInstance();
				var methodName = matcher.group(2);
				var method = ruleClass.getMethod((methodName != null) ? methodName : DEFAULT_JAVA_NATIVE_METHOD, JavaRuleContext.class);

				return method.invoke(ruleExecutor, new JavaRuleContext(context, argumentsMap));
			}
			catch (InvocationTargetException e)
			{
				Throwable targetException = e.getTargetException();
				if (targetException instanceof GeneralException)
				{
					throw (GeneralException) targetException;
				}
				throw new GeneralException(targetException);
			}
			catch (Exception e)
			{
				throw new GeneralException("Unable to execute javanative rule " + rule.getName(), e);
			}
		}
		else
		{
			return context.runRule(rule, argumentsMap);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> computeTargetArguments(Rule proxyRule, Map<String, Object> proxyRuleArguments, Map<String, Object> environment)
	{
		var result = new HashMap<String, Object>();
		var argumentTemplates = (Map<String, String>) proxyRule.getAttributeValue("targetRuleArguments");
		if (argumentTemplates != null)
		{
			var placeholderFinder = Pattern.compile("\\$\\((\\w+)(?:,([^)]*))?\\)");
			for (var templateDefinition : argumentTemplates.entrySet())
			{
				String argumentTemplate = templateDefinition.getValue();
				var placeholderMatcher = placeholderFinder.matcher(argumentTemplate);
				if (placeholderMatcher.matches() && (placeholderMatcher.group(2) == null))
				{
					// single placeholder without default -> use argument directly
					result.put(templateDefinition.getKey(), proxyRuleArguments.get(placeholderMatcher.group(1)));
				}
				else
				{
					// otherwise do regular template substitution
					var buffer = new StringBuilder();
					var position = 0;
					while (placeholderMatcher.find(position))
					{
						var substitution = proxyRuleArguments.get(placeholderMatcher.group(1));
						if (substitution == null)
						{
							if (placeholderMatcher.group(2) == null)
							{
								throw new RuntimeException("No value for placeholder: " + placeholderMatcher.group());
							}
							// prevent possible escaping out of quoted string
							substitution = placeholderMatcher.group(2).replace("\"", "\\\"");
						}
						buffer.append(argumentTemplate, position, placeholderMatcher.start());
						buffer.append(substitution);
						position = placeholderMatcher.end();
					}
					buffer.append(argumentTemplate.substring(position));
					result.put(templateDefinition.getKey(), buffer.toString());
				}
			}
		}
		var argumentComputationRules = proxyRule.getSource();
		if (argumentComputationRules != null)
		{
			var computedArguments = CerberusLogic.compile(argumentComputationRules, "Argument").evaluate(OrionQL.wrap(proxyRuleArguments, null, null, environment), true);
			for (var argument: computedArguments.entrySet())
			{
				if (result.containsKey(argument.getKey()))
				{
					throw new RuntimeException(String.format("Conflicting %s definitions in %s", argument.getKey(), proxyRule.getName()));
				}
				result.put(argument.getKey(), argument.getValue());
			}
		}
		return result;
	}
}
