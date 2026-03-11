package com.six.iam.iiq.rulerunner.rules;

import com.six.iam.iiq.rulerunner.RuleRunnerBean;
import com.six.iam.util.*;
import com.six.iam.util.OrionQL.HtmlContainer;

import java.io.IOException;
import java.util.*;
import java.util.Map;
import java.util.regex.Pattern;

import sailpoint.connector.Connector;
import sailpoint.connector.ConnectorFactory;

import sailpoint.object.*;
import sailpoint.tools.CloseableIterator;

public class GenericQuery implements JavaRuleExecutor
{
	/**
	 * This class solves the problem that a CloseableIterator is not an Iterator
	 */
	public static class CloseableIteratorAdapter<InType> implements Iterator<Object>, CloseableIterator<Object>
	{
		private final CloseableIterator<InType> input;

		public CloseableIteratorAdapter(CloseableIterator<InType> input)
		{
			this.input = input;
		}

		@Override
		public boolean hasNext()
		{
			return input.hasNext();
		}

		@Override
		public Object next()
		{
			return input.next();
		}

		@Override
		public void close()
		{
			input.close();
		}
	}

	@SuppressWarnings("unchecked")
	@Override
	public Object execute(JavaRuleContext ruleContext) throws Exception
	{
		var context = ruleContext.getContext();
		var arguments = ruleContext.getArguments();
		var rulerunnerOutput = (RuleRunnerBean.RuleRunnerOutput) arguments.get("rulerunnerOutput");
		var rulerunnerEnvironment = (Map<String, Object>) arguments.get("rulerunnerEnvironment");
		var className = arguments.getString("className");
		var filter = arguments.getString("filter");
		var ordering = arguments.getString("ordering");
		var subquery = arguments.getString("subquery");
		var computations = arguments.getString("computations");
		var selector = arguments.getString("selector");
		var output = arguments.getString("output");
		var grouping = arguments.getString("grouping");
		var postprocessing = arguments.getString("postprocessing");
		var layout = arguments.getString("layout");
		var maxCaptions = arguments.getInt("maxCaptions");
		var maxRows = arguments.getInt("maxRows");
		var maxCache = arguments.getInt("maxCache");

		// load macros
		var orionLibrary = new MacroLibrary("OrionLibrary", context).load().load(context.getUserName());
		var cerberusLibrary = new MacroLibrary("CerberusLibrary", context).load().load(context.getUserName());

		var baseProxy = OrionQL.wrap(null, context, orionLibrary, rulerunnerEnvironment);

		// prepare parameters
		var isAggregation = className.equals("ResourceObject");
		var isExpression = className.equals("Expression");
		var isRule = className.startsWith("Rule:");
		Class<? extends SailPointObject> objectClass = null;
		QueryOptions queryOptions = null;
		Connector connector = null;
		String schemaObjectType = null;
		OrionQL.Accessor expressionExecutor = null;
		Rule rule = null;
		if (isAggregation)
		{
			var parser = Pattern.compile("([^:]+):\\s*(\\S+)\\s*([\\S\\s]+)?").matcher(filter);
			if (!parser.matches())
			{
				throw new RuntimeException("Invalid aggregation specification");
			}
			var application = context.getObject(Application.class, parser.group(1));
			if (application == null)
			{
				throw new RuntimeException("No such application");
			}
			schemaObjectType = parser.group(2);
			var parameterOverrides = parser.group(3);
			if (parameterOverrides != null)
			{
				application.load();
				context.decache();
				var overrides = OrionQL.evaluateAll(OrionQL.compileAll(parameterOverrides, "\r?\n", true, false, orionLibrary), baseProxy);
				for (var entry: overrides.entrySet())
				{
					application.setAttribute(entry.getKey(), entry.getValue());
				}
			}
			connector = ConnectorFactory.getConnector(application, null);
		}
		else if (isExpression)
		{
			expressionExecutor = OrionQL.compile(filter, false, orionLibrary);
		}
		else if (isRule)
		{
			var ruleName = className.replaceFirst("Rule:\\s*", "");
			rule = context.getObject(Rule.class, ruleName);
			if (!RuleRunnerBean.isAuthorized(context.getObjectByName(Identity.class, context.getUserName()), rule, context))
			{
				throw new RuntimeException("Not authorized: " + ruleName);
			}
			if (rule == null)
			{
				throw new RuntimeException("No such rule: " + ruleName);
			}
		}
		else
		{
			objectClass = (Class<? extends SailPointObject>) Class.forName("sailpoint.object." + className);
			queryOptions = new QueryOptions();
			if ((filter != null) && !filter.isEmpty())
			{
				var filterFormatter = OrionQL.compile(filter, true, orionLibrary);
				queryOptions.addFilter(Filter.compile(filterFormatter.access(baseProxy)));
			}
			if ((ordering != null) && !ordering.isEmpty())
			{
				var matcher = Pattern.compile("([^ ,]+) *(desc)?").matcher(ordering);
				while (matcher.find())
				{
					queryOptions.addOrdering(matcher.group(1), !"desc".equals(matcher.group(2)));
				}
			}
		}
		var collectionAccessor = ((subquery == null) || subquery.isEmpty()) ? null : OrionQL.compile(subquery, false, orionLibrary);
		var selectionRules = ((selector == null) || selector.isEmpty()) ? null: CerberusLogic.compile(selector, null, cerberusLibrary);
		var postprocessingRules = ((postprocessing == null) || postprocessing.isEmpty()) ? null: CerberusLogic.compile(postprocessing, "Level", cerberusLibrary);
		if (postprocessingRules != null)
		{
			// smoke-test rule level specifications
			IndexedData.transform(baseProxy, postprocessingRules);
		}
		var computationRules = new ArrayList<>();
		if ((computations != null) && !computations.isEmpty())
		{
			var rulesPattern = Pattern.compile("\\AAttribute\\(");
			var macroPattern = Pattern.compile("\\A\\$([^$]*)\\$");
			for (var block: computations.split("(\r?\n){2,}", -1))
			{
				if (block.isEmpty())
				{
					continue;
				}
				var isCerberusLogic = rulesPattern.matcher(block).find();
				if (!isCerberusLogic)
				{
					var macroMatcher = macroPattern.matcher(block);
					if (macroMatcher.find())
					{
						isCerberusLogic = cerberusLibrary.containsKey(macroMatcher.group(1));
					}
				}
				computationRules.add(
						isCerberusLogic ?
								CerberusLogic.compile(block, "Attribute", cerberusLibrary) :
								OrionQL.compileAll(block, "\r?\n", true, false, orionLibrary)
				);
			}
		}

		// prepare output
		TabularData tabler = null;
		IndexedData<Object, Object> indexer = null;
		var countOnly = true;
		if ((grouping != null) && !grouping.isEmpty())
		{
			OrionQL.Accessor mapper = null;
			if ((output != null) && !output.isEmpty())
			{
				if (!"Multiple tables".equals(layout))
				{
					mapper = OrionQL.compile(output, OrionQL.guessInputType(output, orionLibrary), orionLibrary);
				}
				countOnly = false;
			}
			indexer = new IndexedData<>(OrionQL.compileAll(grouping, "\r?\n", false, false, orionLibrary), mapper).set(new TreeMap<>());
		}
		if ((indexer == null) || "Multiple tables".equals(layout))
		{
			tabler = new TabularData(OrionQL.compileAll(output, "\r?\n", null, false, orionLibrary));
		}
		if (indexer == null)
		{
			tabler.setOutputStream(rulerunnerOutput, "UTF-8");
			tabler.writeHtmlHeader();
		}

		// perform query
		rulerunnerOutput.startTimeMonitoring();
		Iterator<Object> resultSet = null;
		var primaryCount = 0;
		var primarySelectedCount = 0;
		var secondaryCount = 0;
		var recordCount = 0;
		try
		{
			var cacheSize = 0;
			if (isAggregation)
			{
				resultSet = new CloseableIteratorAdapter<>(connector.iterateObjects(schemaObjectType, null, null));
			}
			else if (isExpression)
			{
				resultSet = OrionQL.Accessor.asIterable(expressionExecutor.access(baseProxy)).iterator();
			}
			else if (isRule)
			{
				var ruleArguments = new HashMap<String, Object>();
				var signature = rule.getSignature();
				if ((signature != null) && (signature.getArguments() != null))
				{
					for (var argument: signature.getArguments())
					{
						ruleArguments.put(argument.getName(), null);
					}
				}
				if ((filter != null) && !filter.isEmpty())
				{
					ruleArguments.putAll(OrionQL.evaluateAll(OrionQL.compileAll(filter, "\r?\n", true, false, orionLibrary), baseProxy));
				}
				var ruleResult = RuleRunnerBean.runRule(context, rule, ruleArguments, (String) rulerunnerEnvironment.get("baseurl"), null);
				resultSet = (ruleResult instanceof Iterator) ? (Iterator<Object>) ruleResult : OrionQL.Accessor.asIterable(ruleResult).iterator();
			}
			else
			{
				resultSet = new QueryHelper.TransformingIterator<>(QueryHelper.search(context, objectClass, queryOptions), o -> o);
			}
			primaryIteration:
			while (resultSet.hasNext())
			{
				if ((maxRows > 0) && (recordCount >= maxRows))
				{
					rulerunnerOutput.addMessage("Maximum row count was reached");
					break;
				}
				if (rulerunnerOutput.isTerminationRequested(recordCount == 0))
				{
					break;
				}
				if ((maxCache > 0) && (cacheSize >= maxCache))
				{
					baseProxy.clearCache();
					context.decache();
					cacheSize = 0;
				}
				var proxy = baseProxy.derive(resultSet.next());
				++primaryCount;
				++cacheSize;
				var selectedRules = (selectionRules == null) ? null : selectionRules.select(proxy);
				if ((selectionRules != null) && (selectedRules == null))
				{
					continue;
				}
				++primarySelectedCount;
				if (collectionAccessor == null)
				{
					computeSyntheticValues(computationRules, proxy);
					if (acceptObject(selectedRules, proxy))
					{
						processRecord(proxy, tabler, indexer, countOnly);
						++recordCount;
					}
				}
				else
				{
					var collection = OrionQL.Accessor.asIterable(collectionAccessor.access(proxy));
					for (var collectionElement: collection)
					{
						if ((maxRows > 0) && (recordCount >= maxRows))
						{
							rulerunnerOutput.addMessage("Maximum row count was reached");
							break primaryIteration;
						}
						if (rulerunnerOutput.isTerminationRequested(false))
						{
							break primaryIteration;
						}
						++secondaryCount;
						var child = (collectionElement instanceof OrionQL.Proxy) ? (OrionQL.Proxy<Object>) collectionElement : proxy.derive(collectionElement);
						computeSyntheticValues(computationRules, child);
						if (acceptObject(selectedRules, child))
						{
							processRecord(child, tabler, indexer, countOnly);
							++recordCount;
						}
					}
				}
			}
		}
		finally
		{
			// finalize output
			if (resultSet instanceof CloseableIterator)
			{
				((CloseableIterator<?>) resultSet).close();
			}
			if (indexer != null)
			{
				var depth = indexer.getDepth();
				var isNumeric = countOnly;
				var isIterable = !countOnly;
				if (postprocessingRules != null)
				{
					var transformed = IndexedData.transform(baseProxy.derive(indexer.get()), postprocessingRules);
					if (!(transformed instanceof Map))
					{
						rulerunnerOutput.addError("Discarding postprocessing result, please return a Map");
						transformed = Collections.emptyMap();
					}
					indexer.set((Map<Object, Object>) transformed);
					var leaf = indexer.peek();
					if (leaf != null)
					{
						depth = leaf.getKey();
						var value = leaf.getValue();
						isNumeric = (value instanceof Number) || ((value instanceof HtmlContainer) && ((HtmlContainer) value).isNumeric());
						isIterable = (value instanceof Iterable);
					}
				}
				if ((layout != null) && !layout.isEmpty() && !"Multiple tables".equals(layout) && (depth > 1))
				{
					var numCaptionLevels = layout.contains("Multiple matrices") ? depth - 2 : 0;
					if ((maxCaptions > 0) && (numCaptionLevels > maxCaptions))
					{
						numCaptionLevels = maxCaptions;
					}
					var haveCellInternalGrouping = depth - numCaptionLevels > 2;
					var rotateHeaders = layout.contains("rotate headers");
					IndexedData.CellMapper cellMapper = null;
					var doTotals = false;
					if (haveCellInternalGrouping)
					{
						cellMapper = IndexedData.CellMapper.PPRINT;
					}
					else if (isIterable)
					{
						cellMapper = IndexedData.CellMapper.JOIN;
					}
					else if (isNumeric)
					{
						doTotals = true;
					}
					indexer.writeHtmlMatrices(numCaptionLevels, rulerunnerOutput, "UTF-8", cellMapper, doTotals, rotateHeaders, true);
				}
				else if ("Multiple tables".equals(layout))
				{
					indexer.writeHtmlTables(rulerunnerOutput, "UTF-8", tabler, true);
				}
				else
				{
					rulerunnerOutput.printNoEscape("<pre>");
					rulerunnerOutput.print(indexer.pprint(0, true));
					rulerunnerOutput.printNoEscape("</pre>");
				}
			}
			else
			{
				tabler.writeHtmlFooter();
			}
		}

		// add summary message
		var groupingAddendum = (indexer == null) ? "" : String.format(", %d primary groups", indexer.get().size());
		if (collectionAccessor == null)
		{
			rulerunnerOutput.addMessage("%d out of %d objects selected in %.02f seconds%s", recordCount, primaryCount, rulerunnerOutput.getDuration(), groupingAddendum);
		}
		else
		{
			rulerunnerOutput.addMessage("%d out of %d objects selected from %d out of %d primary objects in %.02f seconds%s", recordCount, secondaryCount, primarySelectedCount, primaryCount, rulerunnerOutput.getDuration(), groupingAddendum);
		}

		// clear Hibernate cache
		context.decache();

		return ((indexer == null) ? recordCount : indexer.get().size()) > 0;
	}

	/**
	 * Compute and implant synthetic values
 	 */
	@SuppressWarnings("unchecked")
	private void computeSyntheticValues(List<Object> computationRules, OrionQL.Proxy<Object> proxy)
	{
		for (var computationRule: computationRules)
		{
			Map<String, Object> syntheticValues = new HashMap<>();
			if (computationRule instanceof CerberusLogic.RuleSet)
			{
				var selectedRules = ((CerberusLogic.RuleSet) computationRule).select(proxy);
				if (selectedRules != null)
				{
					syntheticValues = selectedRules.evaluate(proxy, true);
				}
			}
			else
			{
				syntheticValues = OrionQL.evaluateAll(((List<OrionQL.Accessor>) computationRule), proxy);
			}
			for (var entry: syntheticValues.entrySet())
			{
				proxy.implant(entry.getKey(), entry.getValue());
			}
		}
	}

	/**
	 * Check object against rules, if any
	 */
	private boolean acceptObject(CerberusLogic.RuleSet selectedRules, OrionQL.Proxy<Object> proxy)
	{
		if (selectedRules == null)
		{
			return true;
		}
		var decision = selectedRules.approve(proxy);
		if ((decision == null) || !decision.isAccept())
		{
			return false;
		}
		proxy.implant("_decision", decision);
		return true;
	}

	/**
	 * Add record to selected output
	 */
	private void processRecord(OrionQL.Proxy<Object> proxy, TabularData tabler, IndexedData<?, Object> indexer, boolean countOnly) throws IOException
	{
		if (indexer == null)
		{
			tabler.writeHtmlRecord(proxy);
		}
		else if (countOnly)
		{
			indexer.count(proxy);
		}
		else
		{
			indexer.add(proxy);
		}
	}
}
