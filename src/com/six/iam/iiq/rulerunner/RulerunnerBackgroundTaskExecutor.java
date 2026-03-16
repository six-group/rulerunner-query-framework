package com.six.iam.iiq.rulerunner;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.log4j.Logger;
import sailpoint.api.SailPointContext;
import sailpoint.object.*;
import sailpoint.task.AbstractTaskExecutor;
import sailpoint.tools.Message;
import sailpoint.tools.xml.XMLObjectFactory;

public class RulerunnerBackgroundTaskExecutor extends AbstractTaskExecutor
{
	private static final int MAX_UNCOMPRESSED_SIZE = 16 * 1024 * 1024;
	private static final Logger logger = Logger.getLogger(RulerunnerBackgroundTaskExecutor.class);

	RuleRunnerBean.RuleRunnerOutput rulerunnerOutput = null;

	@SuppressWarnings("unchecked")
	@Override
	public void execute(SailPointContext sailPointContext, TaskSchedule taskSchedule, TaskResult taskResult, Attributes<String, Object> attributes) throws Exception
	{
		var title = attributes.getString("title");
		var link = attributes.getString("link");
		var baseurl = attributes.getString("baseurl");
		var ruleName = attributes.getString("ruleName");
		var argumentsString = attributes.getString("arguments");
		var webroot = attributes.getString("webroot");
		var emailAddress = attributes.getString("email");
		var emailTemplate = attributes.getString("emailTemplate");
		var conditionalEmail = attributes.getBoolean("conditionalEmail");
		var conditionalErrorLogMessage = attributes.getString("conditionalErrorLogMessage");
		var conditionalErrorLogIncludeOutput = attributes.getBoolean("conditionalErrorLogIncludeOutput");
		taskResult.setAttribute("title", title);
		taskResult.setAttribute("link", link);
		taskResult.setAttribute("ruleName", ruleName);
		taskResult.setAttribute("arguments", argumentsString);
		taskResult.setAttribute("webroot", webroot);
		taskResult.setAttribute("email", emailAddress);
		taskResult.setAttribute("emailTemplate", emailTemplate);
		taskResult.setAttribute("conditionalEmail", conditionalEmail ? "true" : "false");
		taskResult.setAttribute("conditionalErrorLogMessage", conditionalErrorLogMessage);
		taskResult.setAttribute("conditionalErrorLogIncludeOutput", conditionalErrorLogIncludeOutput ? "true" : "false");
		sailPointContext.commitTransaction();
		// fail early if email template does not exist
		var email = new EmailTemplate();
		if (emailTemplate != null)
		{
			email = sailPointContext.getObject(EmailTemplate.class, emailTemplate);
			if (email == null)
			{
				taskResult.addMessage(Message.error(String.format("No such email template: %s", emailTemplate)));
				sailPointContext.commitTransaction();
				return;
			}
			email.load();
		}
		var taskMessages = new ArrayList<Message>();
		rulerunnerOutput = new RuleRunnerBean.RuleRunnerOutput(title, link, webroot, taskMessages);
		var arguments = (Map<String, Object>) XMLObjectFactory.getInstance().parseXml(sailPointContext, argumentsString, false);
		Object result = null;
		try
		{
			var rule = RuleRunnerBean.loadRule(sailPointContext, ruleName);
			if (!RuleRunnerBean.isProxyRule(rule))
			{
				rulerunnerOutput.setLogExceptionStacktrace();
			}
			result = RuleRunnerBean.runRule(sailPointContext, rule, arguments, baseurl, rulerunnerOutput);
		}
		catch (Exception e)
		{
			rulerunnerOutput.addError(e);
		}
		rulerunnerOutput.finishOutput();
		for (var message: taskMessages)
		{
			taskResult.addMessage(message);
		}
		if (rulerunnerOutput.isTerminated())
		{
			taskResult.setTerminated(true);
		}
		sailPointContext.commitTransaction();
		if (((emailAddress != null) || (email.getTo() != null)) && (!conditionalEmail || ((result != null) && !Boolean.FALSE.equals(result))))
		{
			var emailOptions = new EmailOptions();
			if (emailAddress != null)
			{
				email.setTo(emailAddress);
			}
			if (emailTemplate == null)
			{
				email.setSubject(String.format("RuleRunner result for %s", title));
				email.setBody(String.format("<html><body><a href=\"%s\">%s</a> was executed in background. See attachment for result.\n%s</body></html>", link, title, rulerunnerOutput.getMessages()));
			}
			else
			{
				emailOptions.setVariable("title", title);
				emailOptions.setVariable("link", link);
				emailOptions.setVariable("result", result);
				emailOptions.setVariable("messages", rulerunnerOutput.getMessages());
			}
			var dataSent = "Email with no data";
			if (rulerunnerOutput.size() > 0)
			{
				byte[] attachment = rulerunnerOutput.toByteArray();
				String attachmentFilename = rulerunnerOutput.getOutputFileName();
				EmailFileAttachment.MimeType attachmentMimeType = rulerunnerOutput.getAttachmentMimeType();
				dataSent = String.format("%s of output", formatSize(rulerunnerOutput.size()));
				if (rulerunnerOutput.size() > MAX_UNCOMPRESSED_SIZE)
				{
					var zipfile = new ByteArrayOutputStream();
					ZipOutputStream zipWriter = new ZipOutputStream(zipfile);
					zipWriter.putNextEntry(new ZipEntry(attachmentFilename));
					zipWriter.write(attachment);
					zipWriter.closeEntry();
					zipWriter.close();
					attachment = zipfile.toByteArray();
					attachmentFilename = String.format("%s.zip", attachmentFilename.substring(0, attachmentFilename.lastIndexOf('.')));
					attachmentMimeType = EmailFileAttachment.MimeType.MIME_ZIP;
					dataSent = String.format("%s (%s compressed)", dataSent, formatSize(zipfile.size()));
				}
				emailOptions.addAttachment(new EmailFileAttachment(attachmentFilename, attachmentMimeType, attachment));
			}
			sailPointContext.sendEmailNotification(email, emailOptions);
			taskResult.addMessage(Message.info(String.format("%s sent to %s", dataSent, email.getTo())));
			sailPointContext.commitTransaction();
		}
		if ((conditionalErrorLogMessage != null) && (result != null) && !Boolean.FALSE.equals(result))
		{
			var logMessage = new StringBuilder(conditionalErrorLogMessage);
			if (!Boolean.TRUE.equals(result))
			{
				logMessage.append(": ").append(result);
			}
			for (var message: taskMessages)
			{
				logMessage.append("\n").append(message.getLocalizedMessage());
			}
			if (conditionalErrorLogIncludeOutput)
			{
				logMessage.append("\n").append(rulerunnerOutput.toString());
			}
			logger.error(logMessage);
			taskResult.addMessage(Message.info("Message written to log output"));
			sailPointContext.commitTransaction();
		}
	}

	private static String formatSize(int size)
	{
		if (size > 1024 * 1024)
		{
			return String.format("%.01f MB", size / 1048576.0);
		}
		else if (size > 1024)
		{
			return String.format("%.01f kB", size / 1024.0);
		}
		else
		{
			return String.format("%d bytes", size);
		}
	}

	@Override
	public boolean terminate()
	{
		rulerunnerOutput.requestTermination();
		return true;
	}
}
