package com.six.iam.iiq.rulerunner.rules;

import com.six.iam.iiq.rulerunner.RuleRunnerBean;
import com.six.iam.util.IndexedData;
import com.six.iam.util.TabularData;

import java.io.*;
import java.util.*;
import sailpoint.api.*;
import sailpoint.connector.*;
import sailpoint.object.*;
import sailpoint.tools.GeneralException;
import sailpoint.tools.Util;

public class ConnectorTest implements JavaRuleExecutor
{
	private static final int OPCODE_List = 1;
	private static final int OPCODE_Test_Connection = 2;
	private static final int OPCODE_Iterate_Groups = 3;
	private static final int OPCODE_Iterate_Accounts = 4;
	private static final int OPCODE_Regular_Aggregation = 5;
	private static final int OPCODE_Random_Read = 6;
	private static final int OPCODE_Attribute_Provisioning = 7;
	private static final int OPCODE_Entitlement_Provisioning = 8;
	private static final int OPCODE_Account_Enabling_Disabling = 9;
	private static final int OPCODE_Account_Enabling_Only = 109;
	private static final int OPCODE_Account_Disabling_Only = 209;
	private static final int OPCODE_Account_Creation_Deletion = 10;
	private static final int OPCODE_Account_Creation_Only = 110;
	private static final int OPCODE_Full_Account_Provisioning_Lifecycle = 11;

	private static final String[] OPERATIONS = "List,Test Connection,Iterate Groups,Iterate Accounts,Regular Aggregation,Random Read,Attribute Provisioning,Entitlement Provisioning,Account Enabling/Disabling,Account Creation/Deletion,Full Account Provisioning Lifecycle".split(",", -1);
	private static final int DEFAULT_WAIT_TIME = 10;

	private SailPointContext context;
	private String identitySelector;
	private String attributeSelector;
	private int retries;
	private int waitTime;
	private int numIterations;
	private boolean toggleSimulation;
	private boolean emulateRead;
	private boolean verbose;
	private int testSuiteIndex;
	private String suiteName;
	private int[] steps;
	private int numRepetitions;

	/**
	 * aggregationTasks = { applicationName -> { aggregationType -> taskName } }
	 */
	private final HashMap<String, Object> aggregationTasks = new HashMap<>();
	/**
	 * simulated = { applicationName -> integrationConfigName }
	 */
	private final HashMap<String, String> simulated = new HashMap<>();
	/**
	 * knownAccounts = { applicationName -> { nativeIdentity -> [identityName,...] } }
	 */
	private final HashMap<String, Object> knownAccounts = new HashMap<>();
	/**
	 * accountsIndex = { applicationName -> [nativeIdentity,...] }
	 */
	private final HashMap<String, List<String>> accountsIndex = new HashMap<>();
	/**
	 * identityAccounts = { identityName -> { applicationName -> nativeIdentity } }
	 */
	private final HashMap<String, Object> identityAccounts = new HashMap<>();
	/**
	 * accountCreationCandidates = { applicationName -> [identityName,...] }
	 */
	private final HashMap<String, List<String>> accountCreationCandidates = new HashMap<>();
	/**
	 * entitlementsIndex = { applicationName -> { attribute -> [value, ...] } }
	 */
	private final HashMap<String, Object> entitlementsIndex = new HashMap<>();

	private Provisioner provisioner;
	private TaskManager taskManager;
	private Identitizer identitizer;
	private TabularData output;
	private int applicationCount = 0;
	private int globalErrors = 0;

	private class ApplicationTest
	{
		private final String applicationName;
		private Application application;
		private ManagedResource managedResource;

		private ApplicationTest(String applicationName)
		{
			this.applicationName = applicationName;
		}

		public boolean execute() throws Exception
		{
			application = context.getObject(Application.class, applicationName);
			++applicationCount;
			if (!checkApplicationFeatures())
			{
				return false;
			}
			if (!toggleSimulation(false))
			{
				return false;
			}
			// ensure any changed application configuration will be seen
			context.decache();
			application = context.getObject(Application.class, applicationName);
			var repetitionsLeft = numRepetitions;
			while (--repetitionsLeft >= 0)
			{
				var suiteTest = new SuiteTest(applicationName, application);
				if (!suiteTest.execute())
				{
					break;
				}
			}
			return toggleSimulation(true);
		}

		private boolean checkApplicationFeatures() throws IOException
		{
			if ((testSuiteIndex > OPCODE_Regular_Aggregation) && !emulateRead && (application.getFeatures() != null) && application.getFeatures().contains(Application.Feature.NO_RANDOM_ACCESS))
			{
				output.writeHtmlRecord(List.of(applicationName, suiteName, "Random access not available"));
				return false;
			}
			if ((testSuiteIndex > OPCODE_Random_Read) && ((application.getFeatures() == null) || !application.getFeatures().contains(Application.Feature.PROVISIONING) && !application.getFeatures().contains(Application.Feature.SYNC_PROVISIONING)))
			{
				output.writeHtmlRecord(List.of(applicationName, suiteName, "Provisioning is not supported"));
				return false;
			}
			return true;
		}

		private boolean toggleSimulation(boolean restore) throws IOException
		{
			if (restore)
			{
				if (managedResource != null)
				{
					try
					{
						var integrationConfig = context.getObject(IntegrationConfig.class, simulated.get(applicationName));
						integrationConfig.add(managedResource);
						context.commitTransaction();
						output.writeHtmlRecord(List.of(applicationName, "Reconfiguring", String.format("[%tF %1$tT] Restored IntegrationConfig %s", new Date(), integrationConfig.getName())));
					}
					catch (Exception e)
					{
						output.writeHtmlRecord(List.of(applicationName, "Reconfiguring", String.format("[%tF %1$tT] Error restoring IntegrationConfig %s: %s", new Date(), simulated.get(applicationName), e.getMessage())));
						return false;
					}
				}
			}
			else
			{
				managedResource = null;
				if ((testSuiteIndex > OPCODE_Random_Read) && simulated.containsKey(applicationName))
				{
					if (toggleSimulation)
					{
						try
						{
							var integrationConfig = context.getObject(IntegrationConfig.class, simulated.get(applicationName));
							managedResource = integrationConfig.getManagedResource(applicationName);
							integrationConfig.removeManagedResource(application);
							context.commitTransaction();
							output.writeHtmlRecord(List.of(applicationName, "Reconfiguring", String.format("[%tF %1$tT] Removed %s from IntegrationConfig %s", new Date(), applicationName, integrationConfig.getName())));
						}
						catch (Exception e)
						{
							output.writeHtmlRecord(List.of(applicationName, "Reconfiguring", String.format("[%tF %1$tT] Error modifying IntegrationConfig %s: %s", new Date(), simulated.get(applicationName), e.getMessage())));
							return false;
						}
					}
					else
					{
						output.writeHtmlRecord(List.of(applicationName, suiteName, "Simulated"));
						return false;
					}
				}
			}
			return true;
		}
	}

	private class SuiteTest
	{
		private final String applicationName;
		private final Application application;
		/**
		 * in the full suite we will hold the account to use here (set by the first step)
		 */
		private Link pinnedAccount = null;
		private int stepCount = 0;
		private int errorCount = 0;

		private SuiteTest(String applicationName, Application application)
		{
			this.applicationName = applicationName;
			this.application = application;
		}

		public boolean execute() throws GeneralException, IOException
		{
			var suiteStart = System.currentTimeMillis();
			var messages = new ArrayList<String>();
			var continueTesting = true;
			for (var step: steps)
			{
				if (pinnedAccount != null)
				{
					reloadLink(pinnedAccount, context, null);
				}
				++stepCount;
				var start = System.currentTimeMillis();
				try
				{
					switch (step)
					{
						case OPCODE_List:
						{
							listSchemaAttributes(messages);
							break;
						}
						case OPCODE_Test_Connection:
						{
							testConnection(messages);
							break;
						}
						case OPCODE_Iterate_Groups:
						{
							iterateGroups(messages);
							break;
						}
						case OPCODE_Iterate_Accounts:
						{
							iterateAccounts(messages);
							break;
						}
						case OPCODE_Regular_Aggregation:
						{
							runAggregations(messages);
							break;
						}
						case OPCODE_Random_Read:
						{
							continueTesting = readRandomAccount(messages) && continueTesting;
							break;
						}
						case OPCODE_Attribute_Provisioning:
						{
							continueTesting = testAttributeProvisioning(messages) && continueTesting;
							break;
						}
						case OPCODE_Entitlement_Provisioning:
						{
							continueTesting = testEntitlementProvisioning(messages) && continueTesting;
							break;
						}
						case OPCODE_Account_Enabling_Disabling:
						case OPCODE_Account_Enabling_Only:
						case OPCODE_Account_Disabling_Only:
						{
							continueTesting = testEnableDisable(messages, step) && continueTesting;
							break;
						}
						case OPCODE_Account_Creation_Deletion:
						case OPCODE_Account_Creation_Only:
						{
							continueTesting = testCreateDelete(messages, step) && continueTesting;
							break;
						}
					}
				}
				catch (Exception e)
				{
					var writer = new StringWriter();
					writer.write(String.format("%s (%.03f s)", e.getMessage(), (System.currentTimeMillis() - start) / 1000.));
					if (verbose)
					{
						writer.write("\n");
						e.printStackTrace(new PrintWriter(writer));
					}
					addMessage(messages, writer.toString());
					++errorCount;
				}
				// abort the test suite on error (for a single test or a completed suite the loop would end anyway)
				if (pinnedAccount == null)
				{
					break;
				}
			}
			if (stepCount > 1)
			{
				messages.add(String.format("%d errors executing %d steps (%.03f s)", errorCount, stepCount, (System.currentTimeMillis() - suiteStart) / 1000.));
			}
			output.writeHtmlRecord(List.of(applicationName, suiteName, String.join("\n", messages)));
			globalErrors += errorCount;
			return continueTesting;
		}

		private void listSchemaAttributes(List<String> messages)
		{
			messages.add(String.format(
					"%s%s",
					(simulated.containsKey(applicationName)) ? "[Simulated] " : "",
					application.getType()
			));
			var accountSchema = application.getAccountSchema();
			messages.add(String.format("Identity attribute: %s", accountSchema.getIdentityAttribute()));
			messages.add(String.format("Attributes: %s", String.join(", ", accountSchema.getAttributeNames())).replaceAll(".{152}[^,]*,", "$0\n "));
			messages.add(String.format("Entitlement attributes: %s", String.join(", ", accountSchema.getEntitlementAttributeNames())));
		}

		private void testConnection(List<String> messages) throws ConnectorException, GeneralException
		{
			var start = System.currentTimeMillis();
			ConnectorFactory.getConnector(application, null).testConfiguration();
			addMessage(messages, String.format("Success (%.03f s)", (System.currentTimeMillis() - start) / 1000.));
		}

		private void iterateGroups(List<String> messages) throws ConnectorException, GeneralException
		{
			var start = System.currentTimeMillis();
			var groups = readGroups(ConnectorFactory.getConnector(application, null), application.getSchemas(), numIterations);
			if (groups.isEmpty())
			{
				messages.add("No group schemas");
			}
			else
			{
				for (var entry: groups.entrySet())
				{
					addMessage(messages, String.format("Success reading %d %s objects", entry.getValue().size(), entry.getKey()));
				}
				addMessage(messages, String.format("Success reading groups (%.03f s)", (System.currentTimeMillis() - start) / 1000.));
			}
		}

		private void iterateAccounts(List<String> messages) throws ConnectorException, GeneralException
		{
			var start = System.currentTimeMillis();
			var accounts = connectorIterate(ConnectorFactory.getConnector(application, null), "account", numIterations);
			addMessage(messages, String.format("Success reading %d account objects (%.03f s)", accounts.size(), (System.currentTimeMillis() - start) / 1000.));
		}

		private void runAggregations(List<String> messages) throws GeneralException, InterruptedException
		{
			var start = System.currentTimeMillis();
			if (application.getSchemas().size() > 1)
			{
				if (!runAggregation(taskManager, applicationName, "AccountGroupAggregation", messages, context))
				{
					++errorCount;
				}
			}
			if (!runAggregation(taskManager, applicationName, "AccountAggregation", messages, context))
			{
				++errorCount;
			}
			addMessage(messages, String.format("Total time %.03f s", (System.currentTimeMillis() - start) / 1000.));

		}

		private boolean readRandomAccount(List<String> messages) throws Exception
		{
			var link = selectLink(null, applicationName, identitySelector, context);
			if (link == null)
			{
				messages.add("No accounts");
				++errorCount;
				return false;
			}
			else
			{
				var start = System.currentTimeMillis();
				var account = readAccount(applicationName, link.getNativeIdentity(), emulateRead, null, true, context);
				if (getOccupiedKeys(account.getAttributes()).equals(getOccupiedKeys(link.getAttributes())))
				{
					addMessage(messages, String.format("Success reading account %s (%.03f s)", link.getNativeIdentity(), (System.currentTimeMillis() - start) / 1000.));
				}
				else
				{
					addMessage(messages, String.format("Differences reading account %s (%.03f s)", link.getNativeIdentity(), (System.currentTimeMillis() - start) / 1000.));
					if (verbose)
					{
						messages.add(account.toXml(false).trim());
						messages.add(link.toXml(false).trim());
					}
					++errorCount;
				}
			}
			return true;
		}

		private boolean testAttributeProvisioning(List<String> messages) throws Exception
		{
			var attributeNames = selectSchemaAttributes(application.getAccountSchema(), attributeSelector, false);
			if (attributeNames.isEmpty())
			{
				messages.add("No attributes available for provisioning test");
				++errorCount;
				return false;
			}
			--stepCount;
			// test provisioning with all selected attributes
			for (var attributeName: attributeNames)
			{
				++stepCount;
				var link = selectLink(pinnedAccount, applicationName, identitySelector, context);
				if (link == null)
				{
					messages.add("No accounts");
					++errorCount;
					return false;
				}
				var account = readAccount(applicationName, link.getNativeIdentity(), emulateRead, null, true, context);
				var oldValue = account.getString(attributeName);
				// uppercase avoids problems with SECOM
				var newValue = "CHANGED";
				if (newValue.equals(oldValue))
				{
					newValue = "_CHANGED_";
				}
				addMessage(messages, String.format("Changing %s for %s:\n\t'%s' -> '%s'", attributeName, link.getNativeIdentity(), oldValue, newValue));
				var start = System.currentTimeMillis();
				var provisioningPlan = createSimpleModifyPlan(link, ProvisioningPlan.Operation.Set, attributeName, newValue);
				provisionWithRetries(provisioner, provisioningPlan, retries, messages, verbose);
				var duration = (System.currentTimeMillis() - start) / 1000.;
				sleep(waitTime);
				var changedAccount = readAccount(applicationName, link.getNativeIdentity(), emulateRead, null, true, context);
				var success = newValue.equals(changedAccount.getAttribute(attributeName));
				addMessage(messages, String.format("%s changing %s (%.03f s)", success ? "Success" : "Error", attributeName, duration));
				if (!success)
				{
					++errorCount;
					continue;
				}
				link = reloadLink(link, context, null);
				++stepCount;
				addMessage(messages, String.format("Restoring %s for %s:\n\t'%s' -> '%s'", attributeName, link.getNativeIdentity(), newValue, oldValue));
				start = System.currentTimeMillis();
				var restorePlan = createSimpleModifyPlan(link, ProvisioningPlan.Operation.Set, attributeName, oldValue);
				provisionWithRetries(provisioner, restorePlan, retries, messages, verbose);
				duration = (System.currentTimeMillis() - start) / 1000.;
				sleep(waitTime);
				var restoredAccount = readAccount(applicationName, link.getNativeIdentity(), emulateRead, null, true, context);
				success = Util.nullSafeEq(oldValue, restoredAccount.getAttribute(attributeName), true, true);
				addMessage(messages, String.format("%s restoring %s (%.03f s)", success ? "Success" : "Error", attributeName, duration));
				if (!success)
				{
					++errorCount;
				}
			}
			return true;
		}

		private boolean testEntitlementProvisioning(List<String> messages) throws Exception
		{
			var entitlementNames = selectSchemaAttributes(application.getAccountSchema(), attributeSelector, true);
			--stepCount;
			// test provisioning with all selected entitlement attributes
			var fatalError = true;
			for (var entitlementAttribute: entitlementNames)
			{
				List<String> entitlements = IndexedData.get(entitlementsIndex, applicationName, entitlementAttribute);
				if (entitlements == null)
				{
					messages.add(String.format("No entitlements available for %s", entitlementAttribute));
					++errorCount;
					continue;
				}
				++stepCount;
				String entitlement = null;
				Link link = null;
				ResourceObject account = null;
				Object oldEntitlementsAttributeValue = null;
				List<String> oldEntitlements = null;
				// find an entitlement and an account that doesn't have it
				for (var remainingTries = 20; remainingTries > 0; --remainingTries)
				{
					entitlement = entitlements.get(new Random().nextInt(entitlements.size()));
					link = selectLink(pinnedAccount, applicationName, identitySelector, context);
					if (link == null)
					{
						messages.add("No accounts");
						++errorCount;
						return false;
					}
					account = readAccount(applicationName, link.getNativeIdentity(), emulateRead, null, true, context);
					oldEntitlementsAttributeValue = account.getAttribute(entitlementAttribute);
					oldEntitlements = Util.otol(oldEntitlementsAttributeValue);
					if (!Util.nullSafeContains(oldEntitlements, entitlement))
					{
						break;
					}
					link = null;
				}
				if (link == null)
				{
					messages.add(String.format("No suitable accounts for testing assignment of %s", entitlementAttribute));
					++errorCount;
					continue;
				}
				fatalError = false;
				if (Util.nullSafeContains(Util.otol(link.getAttribute(entitlementAttribute)), entitlement))
				{
					messages.add(String.format("Cannot add %s %s\n\tto %s\nbecause it will be filtered by IIQ. Run account aggregation first.", entitlementAttribute, entitlement, link.getNativeIdentity()));
					if (verbose)
					{
						messages.add(account.toXml(false).trim());
						messages.add(link.toXml(false).trim());
					}
					++errorCount;
					continue;
				}
				addMessage(messages, String.format("Adding %s %s\n\tto %s", entitlementAttribute, entitlement, link.getNativeIdentity()));
				var start = System.currentTimeMillis();
				var provisioningPlan = createSimpleModifyPlan(link, ProvisioningPlan.Operation.Add, entitlementAttribute, entitlement);
				provisionWithRetries(provisioner, provisioningPlan, retries, messages, verbose);
				var duration = (System.currentTimeMillis() - start) / 1000.;
				sleep(waitTime);
				var changedAccount = readAccount(applicationName, link.getNativeIdentity(), emulateRead, null, true, context);
				var success = Util.nullSafeContains(Util.otol(changedAccount.getAttribute(entitlementAttribute)), entitlement);
				addMessage(messages, String.format("%s adding entitlement (%.03f s)", success ? "Success" : "Error", duration));
				if (!success)
				{
					++errorCount;
					continue;
				}
				link = reloadLink(link, context, null);
				++stepCount;
				addMessage(messages, String.format("Removing %s %s\n\tfrom %s", entitlementAttribute, entitlement, link.getNativeIdentity()));
				start = System.currentTimeMillis();
				var restorePlan = createSimpleModifyPlan(link, ProvisioningPlan.Operation.Remove, entitlementAttribute, entitlement);
				if (!application.getAccountSchema().getAttributeDefinition(entitlementAttribute).isMulti())
				{
					// special handling for single-value entitlement attributes
					restorePlan = createSimpleModifyPlan(link, ProvisioningPlan.Operation.Set, entitlementAttribute, oldEntitlementsAttributeValue);
				}
				provisionWithRetries(provisioner, restorePlan, retries, messages, verbose);
				duration = (System.currentTimeMillis() - start) / 1000.;
				sleep(waitTime);
				var restoredAccount = readAccount(applicationName, link.getNativeIdentity(), emulateRead, null, true, context);
				var restoredEntitlements = Util.otol(restoredAccount.getAttribute(entitlementAttribute));
				success = (
						((Util.nullSafeSize(oldEntitlements) == 0) && (Util.nullSafeSize(restoredEntitlements) == 0)) ||
						new HashSet<>(oldEntitlements).equals(new HashSet<>(restoredEntitlements))
				);
				addMessage(messages, String.format("%s removing entitlement (%.03f s)", success ? "Success" : "Error", duration));
				if (!success)
				{
					++errorCount;
				}
			}
			return !fatalError;
		}

		private boolean testEnableDisable(List<String> messages, int step) throws Exception
		{
			if (!application.getFeatures().contains(Application.Feature.ENABLE))
			{
				messages.add("Disable/Enable feature not available");
				if (testSuiteIndex == OPCODE_Full_Account_Provisioning_Lifecycle)
				{
					--stepCount;
					return true;
				}
				++errorCount;
				return false;
			}
			var link = selectLink(pinnedAccount, applicationName, identitySelector, context);
			if (link == null)
			{
				messages.add("No accounts");
				++errorCount;
				return false;
			}
			if ((step == OPCODE_Account_Enabling_Only) && !pinnedAccount.isDisabled())
			{
				// no need to enable
				--stepCount;
				return true;
			}
			else if ((step == OPCODE_Account_Disabling_Only) && pinnedAccount.isDisabled())
			{
				messages.add("Account is already disabled");
				--stepCount;
				return true;
			}
			var account = readAccount(applicationName, link.getNativeIdentity(), emulateRead, null, true, context);
			var enableThenDisable = Util.otob(account.getAttribute("IIQDisabled"));
			if (enableThenDisable != link.isDisabled())
			{
				addMessage(messages, String.format("Link disabled state differs from disabled state of account %s", link.getNativeIdentity()));
				++errorCount;
				return true;
			}
			addMessage(messages, String.format("%s %s", enableThenDisable ? "Enabling" : "Disabling", link.getNativeIdentity()));
			var start = System.currentTimeMillis();
			var provisioningPlan = createPlan(link, enableThenDisable ? ProvisioningPlan.AccountRequest.Operation.Enable : ProvisioningPlan.AccountRequest.Operation.Disable, null);
			provisionWithRetries(provisioner, provisioningPlan, retries, messages, verbose);
			var duration = (System.currentTimeMillis() - start) / 1000.;
			sleep(waitTime);
			// on some ADs the operation changes the DN
			var changedLink = reloadLink(link, context, messages);
			var changedAccount = readAccount(applicationName, changedLink.getNativeIdentity(), emulateRead, null, true, context);
			var success = (Util.otob(changedAccount.getAttribute("IIQDisabled")) != enableThenDisable);
			addMessage(messages, String.format("%s %s %s (%.03f s)", success ? "Success" : "Error", enableThenDisable ? "enabling" : "disabling", link.getNativeIdentity(), duration));
			if (!success)
			{
				++errorCount;
				return true;
			}
			if (pinnedAccount != null)
			{
				return true;
			}
			++stepCount;
			addMessage(messages, String.format("%s %s", enableThenDisable ? "Re-disabling" : "Re-enabling", changedLink.getNativeIdentity()));
			start = System.currentTimeMillis();
			var restorePlan = createPlan(changedLink, enableThenDisable ? ProvisioningPlan.AccountRequest.Operation.Disable : ProvisioningPlan.AccountRequest.Operation.Enable, null);
			provisionWithRetries(provisioner, restorePlan, retries, messages, verbose);
			duration = (System.currentTimeMillis() - start) / 1000.;
			sleep(waitTime);
			var restoredLink = reloadLink(changedLink, context, messages);
			var restoredAccount = readAccount(applicationName, restoredLink.getNativeIdentity(), emulateRead, null, true, context);
			success = (Util.otob(restoredAccount.getAttribute("IIQDisabled")) == enableThenDisable);
			addMessage(messages, String.format("%s %s %s (%.03f s)", success ? "Success" : "Error", enableThenDisable ? "re-disabling" : "re-enabling", changedLink.getNativeIdentity(), duration));
			if (!success)
			{
				++errorCount;
			}
			return true;
		}

		private boolean testCreateDelete(List<String> messages, int step) throws Exception
		{
			var success = true;
			if (pinnedAccount == null)
			{
				// Creation
				Identity identity = null;
				if ((identitySelector != null) && !identitySelector.isEmpty())
				{
					identity = context.getObject(Identity.class, identitySelector);
				}
				else
				{
					var candidates = accountCreationCandidates.get(applicationName);
					if (!candidates.isEmpty())
					{
						identity = context.getObject(Identity.class, candidates.get(new Random().nextInt(candidates.size())));
					}
				}
				if (identity == null)
				{
					messages.add("Identity not found");
					++errorCount;
					return false;
				}
				pinnedAccount = new Link();
				pinnedAccount.setIdentity(identity);
				pinnedAccount.setApplication(application);
				addMessage(messages, String.format("Creating account for %s", identity.getName()));
				var start = System.currentTimeMillis();
				var provisioningPlan = createPlan(pinnedAccount, ProvisioningPlan.AccountRequest.Operation.Create, createAttributeInitializationRequests(application.getAccountSchema(), attributeSelector));
				var provisioningProject = provisioner.compile(provisioningPlan);
				var nativeIdentity = extractNativeIdentity(provisioningProject, applicationName);
				if (nativeIdentity != null)
				{
					addMessage(messages, String.format("  as %s", nativeIdentity));
					List<String> existingCorrelations = IndexedData.get(knownAccounts, applicationName, nativeIdentity);
					if (existingCorrelations != null)
					{
						addMessage(messages, String.format("Account already known and correlated to %s", String.join(", ", existingCorrelations)));
						success = false;
					}
				}
				if (success)
				{
					provisioningProject = provisionWithRetries(provisioner, provisioningPlan, retries, messages, verbose);
					success = isFullyCommitted(provisioningProject);
				}
				var duration = (System.currentTimeMillis() - start) / 1000.;
				if (success)
				{
					sleep(waitTime);
					var account = (nativeIdentity == null) ?
							readAccount(applicationName, identity.getName(), true, identitizer, true, context) :
							readAccount(applicationName, nativeIdentity, emulateRead, null, true, context);
					if (account == null)
					{
						addMessage(messages, String.format("Account not created (%.03f s)", duration));
						success = false;
					}
					if (success)
					{
						pinnedAccount = context.getUniqueObject(Link.class, Filter.and(
								Filter.eq("application.name", applicationName),
								Filter.eq("nativeIdentity", account.getIdentity())
						));
						if (pinnedAccount == null)
						{
							addMessage(messages, String.format("Account link not created (%.03f s)", duration));
							success = false;
						}
					}
					if (success)
					{
						addMessage(messages, String.format("Success creating account %s (%.03f s)", pinnedAccount.getNativeIdentity(), duration));
						if (pinnedAccount.isDisabled())
						{
							addMessage(messages, "Account was created in disabled state");
						}
					}
				}
				else
				{
					addMessage(messages, String.format("Failed to create account (%.03f s)", duration));
				}
				if (!success)
				{
					pinnedAccount = null;
					++errorCount;
					return true;
				}
			}
			if (step == OPCODE_Account_Creation_Only)
			{
				return true;
			}
			// Deletion
			// ensure the account is still there
			var account = readAccount(applicationName, pinnedAccount.getNativeIdentity(), emulateRead, null, true, context);
			addMessage(messages, String.format("Deleting account %s", pinnedAccount.getNativeIdentity()));
			var start = System.currentTimeMillis();
			var deletePlan = createPlan(pinnedAccount, ProvisioningPlan.AccountRequest.Operation.Delete, null);
			var deleteProject = provisionWithRetries(provisioner, deletePlan, retries, messages, verbose);
			var duration = (System.currentTimeMillis() - start) / 1000.;
			success = isFullyCommitted(deleteProject);
			if (success)
			{
				sleep(waitTime);
				// use iteration instead of random read (cannot be used on AD connectors)
				account = readAccount(applicationName, pinnedAccount.getNativeIdentity(), true, null, false, context);
				if (account == null)
				{
					addMessage(messages, String.format("Success deleting account %s (%.03f s)", pinnedAccount.getNativeIdentity(), duration));
				}
				else
				{
					addMessage(messages, String.format("Account still exists (%.03f s)", duration));
					++errorCount;
				}
			}
			else
			{
				addMessage(messages, String.format("Failed to delete account (%.03f s)", duration));
				++errorCount;
			}
			pinnedAccount = null;
			return true;
		}

		private List<ResourceObject> connectorIterate(Connector connector, String objectType, int objectLimit) throws ConnectorException
		{
			var result = new ArrayList<ResourceObject>();
			var resultSet = connector.iterateObjects(objectType, null, null);
			while (resultSet.hasNext())
			{
				result.add(resultSet.next());
				if ((objectLimit > 0) && (result.size() >= objectLimit))
				{
					break;
				}
			}
			return result;
		}

		private Map<String, List<ResourceObject>> readGroups(Connector connector, List<Schema> schemas, int objectLimit) throws ConnectorException
		{
			var result = new TreeMap<String, List<ResourceObject>>();
			for (var schema: schemas)
			{
				var objectType = schema.getObjectType();
				if (!"account".equals(objectType))
				{
					result.put(objectType, connectorIterate(connector, objectType, objectLimit));
				}
			}
			return result;
		}

		private ResourceObject readAccount(String applicationName, String nativeIdentity, boolean emulate, Identitizer identitizer, boolean checkNotNull, SailPointContext context) throws Exception
		{
			ResourceObject account = null;
			var application = context.getObject(Application.class, applicationName);
			var connector = ConnectorFactory.getConnector(application, null);
			if (emulate)
			{
				var resultSet = connector.iterateObjects("account", null, null);
				while ((account == null) && resultSet.hasNext())
				{
					var candidate = resultSet.next();
					if (identitizer == null)
					{
						if (nativeIdentity.equals(candidate.getIdentity()))
						{
							account = candidate;
						}
					}
					else
					{
						var candidateIdentity = identitizer.correlate(application, candidate);
						if ((candidateIdentity != null) && candidateIdentity.getName().equals(nativeIdentity))
						{
							account = candidate;
						}
					}
				}
				while (resultSet.hasNext())
				{
					resultSet.next();
				}
				return account;
			}
			else
			{
				account = connector.getObject("account", nativeIdentity, null);
			}
			if (checkNotNull && (account == null))
			{
				throw new Exception(String.format("Unable to read account %s", nativeIdentity));
			}
			return account;
		}

		@SuppressWarnings("unchecked")
		private Set<String> getOccupiedKeys(Map<String, Object> attributes)
		{
			var names = new HashSet<String>();
			for (var attribute: attributes.entrySet())
			{
				var value = attribute.getValue();
				if ((value == null) || ((value instanceof List) && ((List<Object>) value).isEmpty()))
				{
					continue;
				}
				names.add(attribute.getKey());
			}
			return names;
		}

		@SuppressWarnings("BooleanMethodIsAlwaysInverted")
		private boolean runAggregation(TaskManager taskManager, String applicationName, String aggregationType, List<String> messages, SailPointContext context) throws InterruptedException, GeneralException
		{
			String taskName = IndexedData.get(aggregationTasks, applicationName, aggregationType);
			if (taskName == null)
			{
				messages.add(String.format("No %s defined", aggregationType));
				return false;
			}
			else
			{
				var arguments = new HashMap<String, Object>();
				arguments.put("applications", applicationName);
				arguments.put("resultName", String.format("%s %tF %2$tT", taskName, new Date()));
				var taskResult = taskManager.runSync(taskName, arguments);
				// a partitioned aggregation will return immediately after launching the partitions
				while (taskResult.getCompletionStatus() == null)
				{
					sleep(1);
					context.decache();
					taskResult = context.getObject(TaskResult.class, taskResult.getId());
				}
				var addonInfo = "AccountAggregation".equals(aggregationType) ? String.format(" optimized = %s,", taskResult.getAttribute("optimized")) : "";
				addMessage(messages, String.format("%s executing %s (total = %s,%s runLength = %d s)", taskResult.getCompletionStatus(), taskName, taskResult.getAttribute("total"), addonInfo, taskResult.getRunLength()));
				var completionStatus = taskResult.getCompletionStatus();
				return (TaskResult.CompletionStatus.Success.equals(completionStatus) || TaskResult.CompletionStatus.Warning.equals(completionStatus));
			}
		}

		private List<String> selectSchemaAttributes(Schema schema, String attributeSelector, boolean selectEntitlements)
		{
			var allAttributeNames = new ArrayList<String>();
			var selectedAttributeNames = new ArrayList<String>();
			// the attribute assignments that may be contained in attributeSelector don't hurt
			var selector = (attributeSelector == null) ? new HashSet<String>() : new HashSet<>(Util.otol(attributeSelector));
			for (var attributeDefinition: schema.getAttributes())
			{
				if (attributeDefinition.isEntitlement() != selectEntitlements)
				{
					continue;
				}
				var attributeName = attributeDefinition.getName();
				allAttributeNames.add(attributeName);
				if (selector.contains(attributeName))
				{
					selectedAttributeNames.add(attributeName);
				}
			}
			if (selectEntitlements && selectedAttributeNames.isEmpty())
			{
				selectedAttributeNames = allAttributeNames;
			}
			return selectedAttributeNames;
		}

		private Link selectLink(Link pinnedAccount, String applicationName, String identitySelector, SailPointContext context) throws GeneralException
		{
			if (pinnedAccount != null)
			{
				return pinnedAccount;
			}
			String nativeIdentity = null;
			if ((identitySelector != null) && !identitySelector.isEmpty())
			{
				nativeIdentity = IndexedData.get(identityAccounts, identitySelector, applicationName);
			}
			else
			{
				var accounts = accountsIndex.get(applicationName);
				if (accounts != null)
				{
					nativeIdentity = accounts.get(new Random().nextInt(accounts.size()));
				}
			}
			if (nativeIdentity == null)
			{
				return null;
			}
			return context.getUniqueObject(Link.class, Filter.and(
					Filter.eq("application.name", applicationName),
					Filter.eq("nativeIdentity", nativeIdentity)
			));
		}

		private Link reloadLink(Link link, SailPointContext context, List<String> messages) throws GeneralException
		{
			context.decache();
			var reloaded = context.getObject(Link.class, link.getId());
			if (pinnedAccount != null)
			{
				pinnedAccount = reloaded;
			}
			if ((messages != null) && !link.getNativeIdentity().equals(reloaded.getNativeIdentity()))
			{
				addMessage(messages, String.format("Native identity has changed: %s", reloaded.getNativeIdentity()));
			}
			return reloaded;
		}

		private ProvisioningPlan createPlan(Link link, ProvisioningPlan.AccountRequest.Operation operation, List<ProvisioningPlan.AttributeRequest> attributeRequests)
		{
			var provisioningPlan = new ProvisioningPlan();
			provisioningPlan.setSource("six_connector_test");
			var accountRequest = new ProvisioningPlan.AccountRequest(operation, link.getApplication().getName(), null, link.getNativeIdentity());
			accountRequest.setTargetIntegration(link.getApplication().getName());
			if (attributeRequests != null)
			{
				for (var attributeRequest: attributeRequests)
				{
					accountRequest.add(attributeRequest);
				}
			}
			provisioningPlan.add(accountRequest);
			provisioningPlan.setIdentity(link.getIdentity());
			return provisioningPlan;
		}

		private ProvisioningPlan createSimpleModifyPlan(Link link, ProvisioningPlan.Operation operation, String attributeName, Object attributeValue)
		{
			return createPlan(link, ProvisioningPlan.AccountRequest.Operation.Modify, List.of(new ProvisioningPlan.AttributeRequest(attributeName, operation, attributeValue)));
		}

		private List<ProvisioningPlan.AttributeRequest> createAttributeInitializationRequests(Schema schema, String attributeSelector)
		{
			var attributeRequests = new ArrayList<ProvisioningPlan.AttributeRequest>();
			if (attributeSelector != null)
			{
				for (var selector: Util.otol(attributeSelector))
				{
					var parts = selector.split("=", 2);
					if (parts.length > 1)
					{
						var attributeDefinition = schema.getAttributeDefinition(parts[0]);
						if (attributeDefinition != null)
						{
							attributeRequests.add(new ProvisioningPlan.AttributeRequest(
									parts[0],
									attributeDefinition.isEntitlement() ? ProvisioningPlan.Operation.Add : ProvisioningPlan.Operation.Set,
									parts[1]
							));
						}
					}
				}
			}
			return attributeRequests;
		}

		private String extractNativeIdentity(ProvisioningProject provisioningProject, String applicationName)
		{
			var plan = provisioningProject.getPlan(applicationName);
			if (plan != null)
			{
				for (var accountRequest: plan.getAccountRequests(applicationName))
				{
					var nativeIdentity = accountRequest.getNativeIdentity();
					if (nativeIdentity != null)
					{
						return nativeIdentity;
					}
				}
			}
			return null;
		}

		private ProvisioningProject provisionWithRetries(Provisioner provisioner, ProvisioningPlan plan, int retries, List<String> messages, boolean verbose) throws GeneralException, InterruptedException
		{
			var project = provisioner.compile(plan);
			while (true)
			{
				provisioner.execute(project);
				var needsRetry = checkProject(project, verbose ? null : messages);
				if (!needsRetry)
				{
					break;
				}
				if (--retries < 0)
				{
					addMessage(messages, "Giving up");
					break;
				}
				addMessage(messages, "Waiting for retry...");
				sleep(DEFAULT_WAIT_TIME);
			}
			if (verbose)
			{
				addMessage(messages, project.toXml(false).trim());
			}
			return project;
		}

		private boolean checkProject(ProvisioningProject project, List<String> messages) throws GeneralException
		{
			var needsRetry = false;
			for (var plan: Util.iterate(project.getPlans()))
			{
				if (messages != null)
				{
					for (var accountRequest: Util.iterate(plan.getAccountRequests()))
					{
						for (var attributeRequest: Util.iterate(accountRequest.getAttributeRequests()))
						{
							logProvisioningErrors(attributeRequest.getResult(), messages);
						}
						logProvisioningErrors(accountRequest.getResult(), messages);
					}
					logProvisioningErrors(plan.getResult(), messages);
				}
				needsRetry = needsRetry || plan.needsRetry();
			}
			return needsRetry;
		}

		private void logProvisioningErrors(ProvisioningResult provisioningResult, List<String> messages) throws GeneralException
		{
			if ((provisioningResult != null) && !provisioningResult.isCommitted())
			{
				addMessage(messages, provisioningResult.toXml(false).trim());
			}
		}

		private boolean isFullyCommitted(ProvisioningProject project)
		{
			var isFullyCommitted = true;
			for (var plan: Util.iterate(project.getPlans()))
			{
				isFullyCommitted = isFullyCommitted && plan.isFullyCommitted();
			}
			return isFullyCommitted;
		}

		private void sleep(int seconds) throws InterruptedException
		{
			if (seconds > 0)
			{
				Thread.sleep(1000L * seconds);
			}
		}

		private void addMessage(List<String> messages, String message)
		{
			messages.add(String.format("[%tF %1$tT] %s", new Date(), message));
		}
	}

	@SuppressWarnings("unused")
	@Override
	public Object execute(JavaRuleContext ruleContext) throws Exception
	{
		context = ruleContext.getContext();
		var arguments = ruleContext.getArguments();
		RuleRunnerBean.RuleRunnerOutput rulerunnerOutput = (RuleRunnerBean.RuleRunnerOutput) arguments.get("rulerunnerOutput");
		var applicationFilter = arguments.getString("applicationFilter");
		var testSuite = arguments.getString("testSuite");
		identitySelector = arguments.getString("identitySelector");
		attributeSelector = arguments.getString("attributeSelector");
		retries = arguments.getInt("retries");
		waitTime = arguments.getInt("waitTime");
		numIterations = arguments.getInt("numIterations");
		toggleSimulation = arguments.getBoolean("toggleSimulation");
		emulateRead = arguments.getBoolean("emulateRead");
		verbose = arguments.getBoolean("verbose");

		// prepare suite
		testSuiteIndex = Integer.parseInt(testSuite);
		suiteName = OPERATIONS[testSuiteIndex - 1];
		steps = (testSuiteIndex == OPCODE_Full_Account_Provisioning_Lifecycle) ?
				// for the full suite, these steps are executed:
				new int[]{ OPCODE_Account_Creation_Only, OPCODE_Account_Enabling_Only, OPCODE_Attribute_Provisioning, OPCODE_Entitlement_Provisioning, OPCODE_Account_Disabling_Only, OPCODE_Account_Creation_Deletion } :
				// for a single test, only this one step is executed
				new int[]{ testSuiteIndex };
		// the tests are repeated numIterations times, except for Connection Test, Iterations and Aggregation
		// (Iterations will use numIterations to limit the number of objects retrieved)
		numRepetitions = ((numIterations == 0) || (testSuiteIndex < OPCODE_Random_Read)) ? 1 : numIterations;

		// prepare data
		rulerunnerOutput.startTimeMonitoring();
		if (testSuiteIndex == OPCODE_Regular_Aggregation)
		{
			buildAggregationTasksIndex(context);
		}
		if ((testSuiteIndex == OPCODE_List) || (testSuiteIndex > OPCODE_Random_Read))
		{
			readSimulationConfig(context);
		}
		var applicationNames = loadApplications(applicationFilter, context);
		if (testSuiteIndex > OPCODE_Regular_Aggregation)
		{
			buildAccountIndexes(applicationNames, context);
		}
		if ((testSuiteIndex == OPCODE_Entitlement_Provisioning) || (testSuiteIndex == OPCODE_Full_Account_Provisioning_Lifecycle))
		{
			buildEntitlementsIndex(applicationNames, context);
		}
		rulerunnerOutput.addMessage("Data preparation took %.03f s", rulerunnerOutput.getDuration());

		// prepare IIQ API workers
		provisioner = (testSuiteIndex > OPCODE_Random_Read) ? new Provisioner(context) : null;
		taskManager = (testSuiteIndex == OPCODE_Regular_Aggregation) ? new TaskManager(context) : null;
		identitizer = (testSuiteIndex > OPCODE_Account_Enabling_Disabling) ? new Identitizer(context) : null;

		// prepare output
		output = new TabularData(List.of("Application=", "Operation=", "Result="), false);
		output.setOutputStream(rulerunnerOutput, "UTF-8");
		output.writeHtmlHeader();

		// perform tests
		rulerunnerOutput.startTimeMonitoring();
		try
		{
			for (var applicationName: applicationNames)
			{
				if (rulerunnerOutput.isTerminationRequested(false))
				{
					break;
				}
				var applicationTest = new ApplicationTest(applicationName);
				if (!applicationTest.execute())
				{
					++globalErrors;
				}
			}
		}
		finally
		{
			output.writeHtmlFooter();
		}
		rulerunnerOutput.addMessage((globalErrors > 0), "%d errors testing %d applications, %.03f s", globalErrors, applicationCount, rulerunnerOutput.getDuration());
		return null;
	}

	private static List<String> loadApplications(String applicationFilter, SailPointContext context) throws GeneralException
	{
		var defaultFilter = "!type.in({ \"DelimitedFile\", \"ICCLocalManagedApplication\", \"SixLocalManagedApplication\", \"Logical\" })";
		var queryOptions = new QueryOptions();
		queryOptions.addFilter(Filter.compile(
				((applicationFilter == null) || applicationFilter.isEmpty()) ?
						defaultFilter :
						applicationFilter
		));
		queryOptions.addOrdering("name", true);
		var applicationNames = new ArrayList<String>();
		var resultSet = context.search(Application.class, queryOptions, "name");
		while (resultSet.hasNext())
		{
			applicationNames.add((String) resultSet.next()[0]);
		}
		return applicationNames;
	}

	@SuppressWarnings("unchecked")
	private void buildAggregationTasksIndex(SailPointContext context) throws GeneralException
	{
		aggregationTasks.clear();
		var resultSet = context.search(
				TaskDefinition.class,
				new QueryOptions(
						Filter.in("type", Arrays.asList("AccountAggregation", "AccountGroupAggregation")),
						Filter.eq("template", false)
				),
				"name, type, arguments"
		);
		// build mapping as:
		//   { applicationName -> { aggregationType -> taskName } }
		while (resultSet.hasNext())
		{
			var row = resultSet.next();
			var applicationNames = Util.getStringList((Map<String, Object>) row[2], "applications");
			if (applicationNames != null)
			{
				for (var applicationName: applicationNames)
				{
					IndexedData.put(aggregationTasks, (String) row[0], applicationName, row[1].toString());
				}
			}
		}
	}

	private void readSimulationConfig(SailPointContext context) throws Exception
	{
		simulated.clear();
		for (var simulationConfig: context.getObjects(IntegrationConfig.class, new QueryOptions(Filter.eq("executor", "com.six.iam.iiq.connector.SimulateProvisioning"))))
		{
			var resources = simulationConfig.getResources();
			if (resources != null)
			{
				for (var resource: resources)
				{
					if (simulated.put(resource.getResourceName(), simulationConfig.getName()) != null)
					{
						throw new Exception(String.format("Multiple simulation configs for %s", resource.getResourceName()));
					}
				}
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void buildAccountIndexes(List<String> applicationNames, SailPointContext context) throws GeneralException
	{
		knownAccounts.clear();
		accountsIndex.clear();
		identityAccounts.clear();
		accountCreationCandidates.clear();
		// identityAccountsRawData = { identityName -> { applicationName -> [nativeIdentity,...] } }
		var identityAccountsRawData = new HashMap<String, Object>();
		// applicationIdentitiesRawData = { applicationName -> { identityName -> count } }
		var applicationIdentitiesRawData = new HashMap<String, Object>();

		// read account data
		var resultSet = context.search(
				Link.class,
				new QueryOptions(Filter.in("application.name", applicationNames)),
				"application.name, nativeIdentity, identity.name"
		);
		while (resultSet.hasNext())
		{
			var row = resultSet.next();
			IndexedData.add(knownAccounts, (String) row[2], (String) row[0], (String) row[1]);
			IndexedData.add(identityAccountsRawData, (String) row[1], (String) row[2], (String) row[0]);
			IndexedData.count(applicationIdentitiesRawData, (String) row[0], (String) row[2]);
		}

		// build accountIndex from only the uniquely used nativeIdentities
		for (var applicationBucket: knownAccounts.entrySet())
		{
			var uniqueNativeIdentities = new ArrayList<String>();
			for (var identityBucket: ((Map<String, List<String>>) applicationBucket.getValue()).entrySet())
			{
				if (identityBucket.getValue().size() == 1)
				{
					uniqueNativeIdentities.add(identityBucket.getKey());
				}
			}
			accountsIndex.put(applicationBucket.getKey(), uniqueNativeIdentities);
		}

		// populate identityAccounts, ignoring all identities with duplicate application accounts
		for (var identityBucket: identityAccountsRawData.entrySet())
		{
			var accountsMap = new HashMap<String, String>();
			for (var accountsBucket: ((Map<String, List<String>>) identityBucket.getValue()).entrySet())
			{
				var accounts = accountsBucket.getValue();
				if (accounts.size() == 1)
				{
					accountsMap.put(accountsBucket.getKey(), accounts.get(0));
				}
			}
			identityAccounts.put(identityBucket.getKey(), accountsMap);
		}

		// determine all active identities and...
		var identities = new HashSet<String>();
		resultSet = context.search(Identity.class, new QueryOptions(Filter.eq("identitytype", "employee"), Filter.eq("inactive", false)), "name");
		while (resultSet.hasNext())
		{
			identities.add((String) resultSet.next()[0]);
		}
		// ...populate accountCreationCandidates with identities NOT having accounts
		for (var applicationName: applicationNames)
		{
			var identitiesWithoutAccount = new HashSet<>(identities);
			var identitiesWithAccount = (Map<String, Integer>) applicationIdentitiesRawData.get(applicationName);
			if (identitiesWithAccount != null)
			{
				identitiesWithoutAccount.removeAll(identitiesWithAccount.keySet());
			}
			accountCreationCandidates.put(applicationName, new ArrayList<>(identitiesWithoutAccount));
		}
	}

	private void buildEntitlementsIndex(List<String> applicationNames, SailPointContext context) throws GeneralException
	{
		entitlementsIndex.clear();
		var resultSet = context.search(
				ManagedAttribute.class,
				new QueryOptions(Filter.in("application.name", applicationNames)),
				"application.name, attribute, value"
		);
		while (resultSet.hasNext())
		{
			var row = resultSet.next();
			IndexedData.add(entitlementsIndex, (String) row[2], (String) row[0], (String) row[1]);
		}
	}
}
