/************************************************************************
 * Copyright (c) 2014-2015 IoT-Solutions e.U.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *  http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ************************************************************************/

package iot.jcypher.domainquery.internal;

import iot.jcypher.domain.IDomainAccess;
import iot.jcypher.domain.internal.DomainAccess.InternalDomainAccess;
import iot.jcypher.domain.internal.IIntDomainAccess;
import iot.jcypher.domain.internal.SkipLimitCalc;
import iot.jcypher.domain.internal.SkipLimitCalc.SkipsLimits;
import iot.jcypher.domain.mapping.CompoundObjectType;
import iot.jcypher.domain.mapping.FieldMapping;
import iot.jcypher.domain.mapping.ObjectMapping;
import iot.jcypher.domain.mapping.surrogate.Array;
import iot.jcypher.domain.mapping.surrogate.Collection;
import iot.jcypher.domainquery.api.APIAccess;
import iot.jcypher.domainquery.api.DomainObjectMatch;
import iot.jcypher.domainquery.api.IPredicateOperand1;
import iot.jcypher.domainquery.ast.ConcatenateExpression;
import iot.jcypher.domainquery.ast.ConcatenateExpression.Concatenator;
import iot.jcypher.domainquery.ast.IASTObject;
import iot.jcypher.domainquery.ast.OrderExpression;
import iot.jcypher.domainquery.ast.OrderExpression.OrderBy;
import iot.jcypher.domainquery.ast.Parameter;
import iot.jcypher.domainquery.ast.PredicateExpression;
import iot.jcypher.domainquery.ast.PredicateExpression.Operator;
import iot.jcypher.domainquery.ast.SelectExpression;
import iot.jcypher.domainquery.ast.TraversalExpression;
import iot.jcypher.domainquery.ast.TraversalExpression.Step;
import iot.jcypher.query.JcQuery;
import iot.jcypher.query.JcQueryResult;
import iot.jcypher.query.api.IClause;
import iot.jcypher.query.api.pattern.Node;
import iot.jcypher.query.api.pattern.Relation;
import iot.jcypher.query.api.predicate.BooleanOperation;
import iot.jcypher.query.api.predicate.Concat;
import iot.jcypher.query.api.returns.RSortable;
import iot.jcypher.query.factories.clause.OPTIONAL_MATCH;
import iot.jcypher.query.factories.clause.RETURN;
import iot.jcypher.query.factories.clause.SEPARATE;
import iot.jcypher.query.factories.clause.WHERE;
import iot.jcypher.query.factories.clause.WITH;
import iot.jcypher.query.result.JcError;
import iot.jcypher.query.result.JcResultException;
import iot.jcypher.query.values.JcNode;
import iot.jcypher.query.values.JcNumber;
import iot.jcypher.query.values.JcPath;
import iot.jcypher.query.values.ValueAccess;
import iot.jcypher.query.values.ValueElement;
import iot.jcypher.query.writer.Format;
import iot.jcypher.util.Util;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

public class QueryExecutor implements IASTObjectsContainer {

	private static final String idPrefix = "id_";
	private static final String countPrefix = "cnt_";
	private static final String tmpNodePostPrefix = "_t";
	private static final String collNodePostPrefix = "_c";
	private static final char separator = '_';
	
	private IDomainAccess domainAccess;
	private List<IASTObject> astObjects;
	private List<OrderExpression> orders;
	private List<DomainObjectMatch<?>> domainObjectMatches;
	private Map<String, Parameter> parameters;
	private MappingInfo mappingInfo;
	private QueryContext queryResult;
	private QueryContext countResult;
	
	public QueryExecutor(IDomainAccess domainAccess) {
		super();
		this.domainAccess = domainAccess;
		this.astObjects = new ArrayList<IASTObject>();
		this.domainObjectMatches = new ArrayList<DomainObjectMatch<?>>();
		this.parameters = new HashMap<String, Parameter>();
	}

	@Override
	public void addAstObject(IASTObject astObj) {
		this.astObjects.add(astObj);
	}
	
	public OrderExpression getOrderFor(DomainObjectMatch<?> dom) {
		if (this.orders == null)
			this.orders = new ArrayList<OrderExpression>();
		OrderExpression ret = null;
		for (OrderExpression oe : this.orders) {
			if (oe.getObjectMatch().equals(dom)) {
				ret = oe;
				break;
			}
		}
		if (ret == null) {
			ret = new OrderExpression(dom);
			this.orders.add(ret);
		}
		return ret;
	}

	public List<DomainObjectMatch<?>> getDomainObjectMatches() {
		return domainObjectMatches;
	}
	
	/**
	 * Get or create, if not exists, a query parameter.
	 * @param name of the parameter
	 * @return a query parameter
	 */
	public Parameter parameter(String name) {
		Parameter param = this.parameters.get(name);
		if (param == null) {
			param = new Parameter(name);
			this.parameters.put(name, param);
		}
		return param;
	}
	
	/**
	 * Execute the domain query
	 */
	public void execute() {
		executeInternal(false);
	}
	
	/**
	 * Execute the count query
	 */
	public void executeCount() {
		executeInternal(true);
	}
	
	private void executeInternal(boolean execCount) {
		QueryContext context = new QueryContext(execCount);
		QueryBuilder qb = new QueryBuilder();
		List<JcQuery> queries = qb.buildQueries(context);
		Util.printQueries(queries, execCount ? "COUNT QUERY" : "DOM QUERY", Format.PRETTY_1);
		List<JcQueryResult> results = ((IIntDomainAccess)domainAccess).getInternalDomainAccess().
														execute(queries);
		List<JcError> errors = Util.collectErrors(results);
		if (errors.size() > 0) {
			throw new JcResultException(errors);
		}
//		Util.printResults(results, execCount ? "COUNT QUERY" : "DOM QUERY", Format.PRETTY_1);
		if (context.execCount) {
			qb.extractCounts(results, context.resultsPerType);
			this.countResult = context;
		} else {
			qb.extractUniqueIds(results, context.resultsPerType);
			context.queryExecuted();
			this.queryResult = context;
		}
	}
	
	public MappingInfo getMappingInfo() {
		if (this.mappingInfo == null)
			this.mappingInfo = new MappingInfo();
		return this.mappingInfo;
	}
	
	public <T> List<T> loadResult(DomainObjectMatch<T> match) {
		if (APIAccess.isPageChanged(match)) // need to execute the query
			execute();
		if (this.queryResult == null)
			throw new RuntimeException("query was not executed, call execute() on DomainQuery");
		List<QueryContext.ResultsPerType> resPerTypeList = this.queryResult.resultsMap.get(match);
		if (resPerTypeList == null)
			throw new RuntimeException("DomainObjectMatch was not defined in this query");
		Map<Class<?>, List<Long>> type2IdsMap = new HashMap<Class<?>, List<Long>>();
		List<Long> allIds = new ArrayList<Long>();
		for (QueryContext.ResultsPerType resPerType : resPerTypeList) {
			List<Long> ids = type2IdsMap.get(resPerType.type);
			boolean addList = false;
			if (ids == null) {
				ids = new ArrayList<Long>();
				addList = true;
			}
			ids.addAll(resPerType.ids);
			allIds.addAll(resPerType.ids);
			if (ids.isEmpty())
				addList = false;
			if (addList)
				type2IdsMap.put(resPerType.type, ids);
		}
		long[] idsArray = new long[allIds.size()];
		for (int i = 0; i < allIds.size(); i++) {
			idsArray[i] = allIds.get(i).longValue();
		}
		List<T> ret = ((IIntDomainAccess)domainAccess).getInternalDomainAccess().
			loadByIds(APIAccess.getDomainObjectType(match), type2IdsMap, -1, idsArray);
		return ret;
	}
	
	public long getCountResult(DomainObjectMatch<?> match) {
		long res = 0;
		if (this.countResult == null)
			throw new RuntimeException("query was not executed, call executeCount() on DomainQuery");
		List<QueryContext.ResultsPerType> resPerTypeList = this.countResult.resultsMap.get(match);
		if (resPerTypeList == null)
			throw new RuntimeException("DomainObjectMatch was not defined in this query");
		for (QueryContext.ResultsPerType resPerType : resPerTypeList) {
			res = res + resPerType.count;
		}
		return res;
	}
	
	private QueryContext loadCountResultIfNeeded() {
		if (this.countResult == null) {
			executeCount();
		}
		return this.countResult;
	}
	
	/************************************/
	private class QueryBuilder {
		
		private ClauseBuilder clauseBuilder = new ClauseBuilder();
		
		List<JcQuery> buildQueries(QueryContext context) {
			List<ClausesPerType> clausesPerTypeList = new ArrayList<ClausesPerType>();
			List<ExpressionsPerDOM> xpressionsPerDom = new ArrayList<ExpressionsPerDOM>();
			for (DomainObjectMatch<?> dom : domainObjectMatches) {
				ExpressionsPerDOM xpd = buildExpressionsPerDOM(dom, astObjects);
				if (xpd != null)
					xpressionsPerDom.add(xpd);
			}
			
			xpressionsPerDom = orderByDependencies(xpressionsPerDom);
			
			ClauseBuilderContext cbContext = new ClauseBuilderContext(new ArrayList<String>());
			for (ExpressionsPerDOM xpd : xpressionsPerDom) {
				clausesPerTypeList.addAll(this.clauseBuilder.buildClausesFor(xpd, cbContext, context.execCount));
			}
			
			return buildQueriesInt(clausesPerTypeList, context);
		}
		
		private ExpressionsPerDOM buildExpressionsPerDOM(DomainObjectMatch<?> dom, List<IASTObject> astObjs) {
			ExpressionsPerDOM ret = null;
			StateContext stateContext = findXpressionsFor(dom, astObjs);
			if (stateContext.state == State.HAS_XPRESSION) {
				ret = new ExpressionsPerDOM(dom, stateContext.candidates,
						stateContext.dependencies);
			} else if (stateContext.state == State.INIT) {
				stateContext.candidates.clear();
				ret = new ExpressionsPerDOM(dom, stateContext.candidates,
						stateContext.dependencies);
			}
			return ret;
		}
		
		private List<JcQuery> buildQueriesInt(List<ClausesPerType> clausesPerTypeList,
				QueryContext context) {
			List<JcQuery> ret = new ArrayList<JcQuery>();
			int queryIndex = -1;
			// if split into multiple queries, due to use of LIMIT, SKIP,
			// which must be attached to a RETURN clause
			int startIdx = 0;
			while(startIdx < clausesPerTypeList.size()) {
				queryIndex++;
				List<IClause> clauses = new ArrayList<IClause>();
				List<ClausesPerType> toReturn = new ArrayList<ClausesPerType>();
				List<IClause> withClauses = null;
				boolean needAddDependencies = startIdx > 0;
				boolean needWith = false;
				int i;
				for (i = startIdx; i < clausesPerTypeList.size(); i++) {
					ClausesPerType cpt = clausesPerTypeList.get(i);
					if (cpt.valid) {
						boolean startNewQueryAfterThis = false;
						if (!context.execCount) { // execute full query (not count query)
							if (withClauses == null)
								withClauses = new ArrayList<IClause>();
							if (this.clauseBuilder.buildAddWithClause(cpt, withClauses))
								needWith = true;
							// we need to split up in multiple queries
							if (cpt.needSkipsLimits()) { // need to add it to the RETURN clause
																		// does not work at the WITH clause
								if (i > startIdx) { // start a new query beginning with this one
									startIdx = i;
									break;
								}
								needWith = true;
								withClauses.add(WHERE.NOT().valueOf(cpt.node).IS_NULL());
								// i == startIdx (must be)
								// start a new query after this one
								startIdx = i + 1;
								startNewQueryAfterThis = true;
							}
						}
						if (needAddDependencies) {
							cpt.addDependencyClauses(clauses);
							needAddDependencies = false;
						}
						clauses.addAll(cpt.getClauses());
						toReturn.add(cpt);
						QueryContext.ResultsPerType resPerType = context.addFor(cpt, context.execCount);
						context.resultsPerType.add(resPerType);
						resPerType.queryIndex = queryIndex;
						if (startNewQueryAfterThis)
							break;
					} else
						context.addEmptyFor(cpt); // did not produce a result
				}
				
				if (i == clausesPerTypeList.size()) // we are finished
					startIdx = i;
				
				List<IClause> returnClauses = buildReturnClauses(toReturn, context.execCount);
				if (needWith)
					clauses.addAll(withClauses);
				clauses.addAll(returnClauses);
				
				IClause[] clausesArray = clauses.toArray(new IClause[clauses.size()]);
				JcQuery query = new JcQuery();
				query.setClauses(clausesArray);
				ret.add(query);
			}
			return ret;
		}

		private List<IClause> buildReturnClauses(List<ClausesPerType> toReturn,
				boolean isCountQuery) {
			List<IClause> ret = new ArrayList<IClause>();
			int idx = 0;
			for (ClausesPerType cpt : toReturn) {
				JcNode n = cpt.node;
				RSortable returnClause;
				if (isCountQuery)
					returnClause = RETURN.count().DISTINCT().value(n).AS(this.getJcNumber(countPrefix, n));
				else {
					if (idx == 0)
						returnClause = RETURN.DISTINCT().value(n.id()).AS(this.getJcNumber(idPrefix, n));
					else
						returnClause = RETURN.value(n.id()).AS(this.getJcNumber(idPrefix, n));
					
					// add skip and limit if required
					if (cpt.pageOffset > 0)
						returnClause = (RSortable)returnClause.SKIP(cpt.pageOffset);
					if (cpt.pageLength >= 0)
						returnClause = (RSortable)returnClause.LIMIT(cpt.pageLength);
				}
				ret.add(returnClause);
				idx++;
			}
			return ret;
		}

		void extractUniqueIds(List<JcQueryResult> results, List<QueryContext.ResultsPerType> resultsPerTypeList) {
			Set<Long> uniqueIds = new LinkedHashSet<Long>();
			for (QueryContext.ResultsPerType resPerType : resultsPerTypeList) {
				uniqueIds.clear();
				JcQueryResult result = results.get(resPerType.queryIndex);
				List<BigDecimal> idList = result.resultOf(resPerType.jcNumber);
				for (BigDecimal bd : idList) {
					if (bd != null) {
						uniqueIds.add(bd.longValue());
					}
				}
				resPerType.ids = new ArrayList<Long>();
				resPerType.ids.addAll(uniqueIds);
			}
			return;
		}
		
		void extractCounts(List<JcQueryResult> results, List<QueryContext.ResultsPerType> resultsPerTypeList) {
			for (QueryContext.ResultsPerType resPerType : resultsPerTypeList) {
				JcQueryResult result = results.get(resPerType.queryIndex);
				List<BigDecimal> count = result.resultOf(resPerType.jcNumber);
				resPerType.count = count.get(0).longValue();
			}
		}
		
		/**
		 * may return null
		 * @param cpt
		 * @return
		 */
		private List<OrderBy> getOrderExpressionsFor(ClausesPerType cpt) {
			List<OrderBy> ret = null;
			OrderExpression oe = getOrderFor(cpt.domainObjectMatch);
			if (oe != null) {
				List<OrderBy> ocs = oe.getOrderCriterias();
				for (OrderBy ob : ocs) {
					String attribName = ob.getAttributeName();
					FieldMapping fm = getMappingInfo().getFieldMapping(attribName, cpt.domainObjectType);
					if (fm != null) { // field exists for type, so we can sort
						if (ret == null)
							ret = new ArrayList<OrderBy>();
						ret.add(ob);
					}
				}
			}
			return ret;
		}

		private List<ExpressionsPerDOM> orderByDependencies(
				List<ExpressionsPerDOM> xpressionsPerDom) {
			List<ExpressionsPerDOM> ordered = new ArrayList<ExpressionsPerDOM>(xpressionsPerDom.size());
			List<ExpressionsPerDOM> tryingToAdd = new ArrayList<ExpressionsPerDOM>(xpressionsPerDom.size());
			
			for (ExpressionsPerDOM xpd : xpressionsPerDom) {
				addRecursive(xpd, xpressionsPerDom, ordered, tryingToAdd);
			}
			
			return ordered;
		}
		
		private void addRecursive(ExpressionsPerDOM add, List<ExpressionsPerDOM> xpressionsPerDom,
				List<ExpressionsPerDOM> ordered, List<ExpressionsPerDOM> tryingToAdd) {
			if (tryingToAdd.contains(add))
				throw new RuntimeException("circular dependencies in WHERE clauses");
			for (ExpressionsPerDOM xp : tryingToAdd) {
				xp.addDependencies(add.flattenedDependencies);
				xp.addDependency(add);
			}
			if (add.dependencies == null || add.dependencies.isEmpty()) {
				if (!ordered.contains(add))
					ordered.add(add);
			} else {
				if (!ordered.contains(add)) {
					tryingToAdd.add(add);
					for (DomainObjectMatch<?> dom : add.dependencies) {
						ExpressionsPerDOM xpd = getXprPerDom(dom, xpressionsPerDom);
						addRecursive(xpd, xpressionsPerDom, ordered, tryingToAdd);
					}
					tryingToAdd.remove(add);
					ordered.add(add);
				}
			}
		}
		
		private ExpressionsPerDOM getXprPerDom(DomainObjectMatch<?> dom,
				List<ExpressionsPerDOM> xpressionsPerDom) {
			for (ExpressionsPerDOM xpd : xpressionsPerDom) {
				if (xpd.domainObjectMatch.equals(dom))
					return xpd;
			}
			return null;
		}

		private SkipsLimits calcSkipsLimits(DomainObjectMatch<?> dom, int offset, int len) {
			QueryContext cRes = loadCountResultIfNeeded();
			List<QueryContext.ResultsPerType> rpts = cRes.resultsMap.get(dom);
			List<Integer> counts = new ArrayList<Integer>(rpts.size());
			for (QueryContext.ResultsPerType rpt : rpts) {
				counts.add((int)rpt.count);
			}
			SkipsLimits slc = SkipLimitCalc.calcSkipsLimits(counts, offset, len);
			return slc;
		}

		/**
		 * @param value
		 * @param op
		 * @param paramPosition 1 or 2
		 */
		private void testValidInOperation(Operator op,
				int paramPosition, PredicateExpression pred) {
			if (op == Operator.CONTAINS) {
				if (!(pred.getValue_1() instanceof ValueElement) &&
						!(pred.isInCollectionExpression() &&
						pred.getValue_1() instanceof DomainObjectMatch<?> &&
						pred.getValue_2() instanceof DomainObjectMatch<?>)) {
					throw new RuntimeException("'CONTAINS' operation on two DomainObjectMatch(es) is only valid within" +
						" a collection expression");
				}
				return; // valid
			}
			
			Object value;
			if (paramPosition == 1) {
				value = pred.getValue_1();
				if (value instanceof DomainObjectMatch<?>) {
					if (!(op == Operator.IN || op == Operator.IS_NULL))
						throw new RuntimeException("invalid parameter 1 in WHERE clause [" + op.name() + "]");
				}
			} else if (paramPosition == 2) {
				value = pred.getValue_2();
				if (value instanceof DomainObjectMatch<?>) {
					if (op != Operator.IN)
						throw new RuntimeException("invalid parameter 2 in WHERE clause [" + op.name() + "]");
				}
			}
		}

		private List<JcNode> collectNodes(DomainObjectMatch<?> dom, List<String> validNodes) {
			List<JcNode> ret = new ArrayList<JcNode>();
			List<JcNode> nodes = APIAccess.getNodes((DomainObjectMatch<?>)dom);
			for(JcNode n : nodes) {
				String nnm = ValueAccess.getName(n);
				if (validNodes.contains(nnm))
					ret.add(n);
			}
			return ret;
		}
		
		/**
		 * @param val
		 * @param validNodes if != null, use list to filter nodes
		 * @return
		 */
		@SuppressWarnings("unchecked")
		private List<Object> buildAllInstances(ValueElement ve, List<String> validNodes) {
			List<Object> ret = new ArrayList<Object>();
			List<JcNode> validFor = null;
			if (ve != null) {
				ValueElement first = ValueAccess.findFirst(ve);
				if (first instanceof JcNode) {
					String nodeName = ValueAccess.getName((JcNode)first);
					if (validNodes.contains(nodeName))
						ret.add(ve);
					if (validFor == null) {
						Object hint = ValueAccess.getAnyHint(ve, APIAccess.hintKey_validNodes);
						if (hint instanceof List<?>) {
							validFor = (List<JcNode>) hint; 
						}
					}
					for(JcNode n : validFor) {
						String nnm = ValueAccess.getName(n);
						if (!nodeName.equals(nnm)) { // need to clone
							if (validNodes.contains(nnm))
								ret.add(cloneVe(ve, first, n));
						}
					}
				} else
					ret.add(ve);
			}
			return ret;
		}

		/**
		 * @param ve
		 * @param clausesPerType
		 * @return the expression or its clone, or null if the expression is not valid for this type
		 */
		private ValueElement testAndCloneIfNeeded(ValueElement ve, ClausesPerType clausesPerType) {
			ValueElement ret = ve;
			if (ve != null) {
				ValueElement first = ValueAccess.findFirst(ve);
				if (first instanceof JcNode) {
					testValidForType((JcNode)first, ve, clausesPerType);
					if (clausesPerType.valid) {
						if(!(ValueAccess.getName((JcNode)first).equals(ValueAccess.getName(clausesPerType.node)))) {
							ret = cloneVe(ve, first, clausesPerType.node);
						}
					} else
						ret = null;
				}
			}
			return ret;
		}
		
		private ValueElement cloneVe(ValueElement ve, ValueElement first,
				ValueElement newFirst) {
			ValueElement ret = ve;
			ValueElement prev = ve;
			ValueElement nextCloned = null;
			while(prev != first) {
				ValueElement cloned = ValueAccess.cloneShallow(prev);
				if (nextCloned != null)
					ValueAccess.setPredecessor(nextCloned, cloned);
				else // in the first iteration
					ret = cloned;
				nextCloned = cloned;
				prev = ValueAccess.getPredecessor(prev);
			}
			if (nextCloned != null) // there was at least one iteration
				ValueAccess.setPredecessor(nextCloned, newFirst);
			else 
				ret = newFirst;
			return ret;
		}

		@SuppressWarnings("unchecked")
		private void testValidForType(JcNode first,
				ValueElement ve, ClausesPerType clausesPerType) {
			if (ve == first) {
				return;
			}
			if (clausesPerType.previousOr || !Settings.strict) { // build the clause even if the expression will return no result
				return;									     // because it is concatenated by OR
			}
			Object hint = ValueAccess.getAnyHint(ve, APIAccess.hintKey_validNodes);
			if (hint instanceof List<?>) {
				List<JcNode> validFor = (List<JcNode>) hint; 
				String nm = ValueAccess.getName(clausesPerType.node);
				for(JcNode n : validFor) {
					if (nm.startsWith(ValueAccess.getName(n))) {
						return;
					}
				}
			}
			clausesPerType.testForNextIsOr = true;
			return;
		}

		private StateContext findXpressionsFor(DomainObjectMatch<?> dom, List<IASTObject> astObjs) {
			String baseNodeName = APIAccess.getBaseNodeName(dom);
			StateContext context = new StateContext(baseNodeName);
			for (int i = 0; i < astObjs.size(); i++) {
				IASTObject astObj = astObjs.get(i);
				testXpressionFor(baseNodeName, astObj, context);
			}
			if (context.blockCount != 0)
				throw new RuntimeException("bracket close mismatch");
			if (context.state == State.HAS_XPRESSION) {
				removeEmptyBlocks(context.candidates);
			}
			return context;
		}

		private void removeEmptyBlocks(List<IASTObject> candidates) {
			List<Integer> toRemove = new ArrayList<Integer>();
			List<Integer> orsToRemove = new ArrayList<Integer>();
			List<Integer> candidatesIndices = new ArrayList<Integer>();
			// key: openBracket index, value: or index
			Map<Integer, Integer> bracketToOr = new HashMap<Integer, Integer>();
			int orIndexToTest = -1;
			boolean orMaybeValid = false; // or must not be at begin
			for (int i = 0; i < candidates.size(); i++) {
				IASTObject astObj = candidates.get(i);
				if (astObj instanceof ConcatenateExpression) {
					Concatenator concat = ((ConcatenateExpression)astObj).getConcatenator();
					if (concat == Concatenator.BR_OPEN) {
						if (orIndexToTest != -1) // or may be before open bracket
							bracketToOr.put(i, orIndexToTest);
						orIndexToTest = -1;
						candidatesIndices.add(i);
						orMaybeValid = false; // or must not follow immediately after an open bracket
					} else if (concat == Concatenator.BR_CLOSE) {
						if (candidatesIndices.size() > 0) { // close follows immediately after open
																				// empty block will be removed
							// idx is the matching open bracket index
							Integer idx = candidatesIndices.remove(candidatesIndices.size() - 1);
							Integer orIdx = bracketToOr.get(idx);
							if (orIdx != null)
								orIndexToTest = orIdx.intValue(); // must be tested if valid
							else
								orIndexToTest = -1; // if != -1was within the block that will be removed
																 // it will be removed with the block so we can forget it
							// test and remove encapsulated pairs
							for (int j = toRemove.size() - 1; j >= 0; j--) {
								if (idx.intValue() < toRemove.get(j).intValue())
									toRemove.remove(j);
							}
							toRemove.add(idx); // start
							toRemove.add(i); // end
						} else {
							orMaybeValid = true; // or can follow a closing bracket
							if (orIndexToTest != -1)
								orsToRemove.add(orIndexToTest);  // or must not be immediately before a close bracket
							orIndexToTest = -1;
						}
					} else if (concat == Concatenator.OR) {
						if (!orMaybeValid)
							orsToRemove.add(i);
						else {
							orIndexToTest = i; // for test with next expression
							orMaybeValid = false; // no consecutive ors allowed
						}
					}
				} else { // not an empty block, contains at least one valid predicate expression
					candidatesIndices.clear();
					orMaybeValid = true; // or can follow a predicate expression
					orIndexToTest = -1;
				}
			}
			if (orIndexToTest != -1)
				orsToRemove.add(orIndexToTest);  // or must not be at the end
			
			// now remove the found empty blocks
			// indices are always pairwise for close and open brackets
			int prevCloseIndex = -1; // the previous close index paired with the actual open index
			// we always start with a close index
			for (int i = toRemove.size() - 1; i >= 0; i--) {
				int idx = toRemove.get(i).intValue();
				if (prevCloseIndex == -1) // the actual is a close index
					candidates.remove(idx);
				else { // the actual is an open index
					for (int j = prevCloseIndex - 1; j >= idx; j--) {
						// remove all between the previous close index (exclusive)
						// and the actual open index (inclusive)
						candidates.remove(j);
					}
					int adjust = prevCloseIndex - idx + 1; // this range is removed
					// remove and adjust or indices
					for (int j = orsToRemove.size() - 1; j >= 0; j--) {
						int oidx = orsToRemove.get(j).intValue();
						if (oidx > idx && oidx < prevCloseIndex) // remove
							orsToRemove.remove(j);
						else if (oidx > prevCloseIndex)
							orsToRemove.set(j, oidx - adjust);
					}
				}
				// toggle between close and open index (they are always pairwise)
				prevCloseIndex = prevCloseIndex == -1 ? idx : -1;
			}
			
			// remove invalid ors
			Collections.sort(orsToRemove);
			for (int i = orsToRemove.size() - 1; i >= 0; i--) {
				int idx = orsToRemove.get(i).intValue();
				candidates.remove(idx);
			}
		}

		private void testXpressionFor(String baseNodeName,
				IASTObject astObj, StateContext context) {
			if (astObj instanceof PredicateExpression) {
				PredicateExpression pred = (PredicateExpression)astObj;
				boolean isXpr = false;
				String nodeName_1 = getNodeName(pred.getValue_1());
				int orRemoveState = 0;
				if (nodeName_1 != null) {
					if (nodeName_1.indexOf(baseNodeName) == 0) {
						if (context.orToAdd != null) // add pending or
							context.candidates.add(context.orToAdd);
						context.candidates.add(astObj);
						isXpr = true;
						context.state = State.HAS_XPRESSION;
						orRemoveState = -1;
					}
				}
				if (isXpr) {
					String nodeName_2 = getNodeName(pred.getValue_2());
					if (nodeName_2 != null) {
						if (nodeName_2.indexOf(baseNodeName) < 0) {
							context.addDependency(nodeName_2);
						}
					}
				}
				context.orRemoveState = orRemoveState;
				context.orToAdd = null; // clear or, it has either been added already or it is invalid
			} else if (astObj instanceof ConcatenateExpression) {
				boolean isOr = false;
				int orRemoveState = -1;
				ConcatenateExpression conc = (ConcatenateExpression)astObj;
				if (conc.getConcatenator() == Concatenator.BR_OPEN) {
					context.blockCount++;
					if (context.orToAdd != null) // add pending or
						context.candidates.add(context.orToAdd);
				} else if (conc.getConcatenator() == Concatenator.BR_CLOSE) {
					if (context.blockCount <= 0)
						throw new RuntimeException("bracket close mismatch");
					context.blockCount--;
				} else if (conc.getConcatenator() == Concatenator.OR) {
					orRemoveState = context.orRemoveState;
					isOr = true;
				}
				context.orToAdd = null; // clear or, it has either been added already or it is invalid
				if (orRemoveState != 0) { // prevoius was not foreign predicate expression
					if (isOr)
						context.orToAdd = astObj; // to test if next is foreign predicate expression
					else
						context.candidates.add(astObj);
				}
				context.orRemoveState = orRemoveState;
			} else if (astObj instanceof TraversalExpression) {
				TraversalExpression te = (TraversalExpression)astObj;
				String bName = APIAccess.getBaseNodeName(te.getEnd());
				if (baseNodeName.equals(bName)) {
					context.candidates.add(astObj);
					context.state = State.HAS_XPRESSION;
					bName = APIAccess.getBaseNodeName(te.getStart());
					context.addDependency(bName);
				}
			} else if (astObj instanceof SelectExpression<?>) {
				SelectExpression<?> se = (SelectExpression<?>)astObj;
				String bName = APIAccess.getBaseNodeName(se.getEnd());
				if (baseNodeName.equals(bName)) {
					context.candidates.add(astObj);
					context.state = State.HAS_XPRESSION;
					bName = APIAccess.getBaseNodeName(se.getStart());
					context.addDependency(bName);
					// add dependencies of predicate expressions within collection expression
					for(IASTObject ao : se.getAstObjects()) {
						if (ao instanceof PredicateExpression) {
							PredicateExpression pred = (PredicateExpression)ao;
							context.addDependency(getNodeName(pred.getValue_1()));
							context.addDependency(getNodeName(pred.getValue_2()));
						}
					}
				}
			}
		}

		private String getNodeName(Object value) {
			if (value instanceof ValueElement) {
				ValueElement ve = (ValueElement)value;
				ValueElement first = ValueAccess.findFirst(ve);
				if (first instanceof JcNode)
					return ValueAccess.getName((JcNode)first);
			} else if (value instanceof DomainObjectMatch<?>) {
				return APIAccess.getBaseNodeName((DomainObjectMatch<?>)value);
			}
			return null;
		}
		
		private JcNumber getJcNumber(String prefix, JcNode n) {
			String nm = ValueAccess.getName(n);
			nm = nm.substring(APIAccess.nodePrefix.length());
			nm = prefix.concat(nm);
			return new JcNumber(nm);
		}
		
		/*************************************/
		private class ClauseBuilder {
			private List<ClausesPerType> buildClausesFor(ExpressionsPerDOM xpd,
					ClauseBuilderContext cbContext, boolean calcCounts) {
				DomainObjectMatch<?> dom = xpd.domainObjectMatch;
				List<IASTObject> xpressionsForDom = xpd.xPressions;
				List<ClausesPerType> clausesPerTypeList = new ArrayList<ClausesPerType>();
				List<JcNode> nodes = APIAccess.getNodes(dom);
				List<Class<?>> typeList = APIAccess.getTypeList(dom);
				int numTypes = typeList.size();
				List<Integer> offsets;
				List<Integer> lens;
				int offset = calcCounts ? 0 : APIAccess.getPageOffset(dom);
				int len = calcCounts ? -1 : APIAccess.getPageLength(dom);
				if (numTypes > 1 &&	(offset > 0 || len >= 0) &&
						APIAccess.isPageChanged(dom)) {
					SkipsLimits slc = calcSkipsLimits(dom, offset, len);
					offsets = slc.getOffsets();
					lens = slc.getLengths();
				} else {
					offsets = new ArrayList<Integer>(numTypes);
					lens = new ArrayList<Integer>(numTypes);
					if (numTypes == 1) {
						offsets.add(offset);
						lens.add(len);
					} else {
						for (int i = 0; i < numTypes; i++) {
							offsets.add(0);
							lens.add(-1);
						}
					}
				}
				int idx = 0;
				for (JcNode n : nodes) {
					ClausesPerType cpt = new ClausesPerType(n, dom, typeList.get(idx));
					cpt.expressionsPerDOM = xpd;
					cpt.pageOffset = offsets.get(idx);
					cpt.pageLength = lens.get(idx);
					clausesPerTypeList.add(cpt);
					idx++;
				}
				
				cbContext.stepClausesCount = 0;
				for (IASTObject astObj : xpressionsForDom) {
					for (ClausesPerType clausePerType : clausesPerTypeList) {
						if (clausePerType.valid) {
							addClause(astObj, clausePerType, cbContext);
						}
					}
				}
				for (ClausesPerType clausePerType : clausesPerTypeList) {
					if (clausePerType.valid) {
						// add node names for which there is (will be) a match clause
						String nm = ValueAccess.getName(clausePerType.node);
						cbContext.validNodes.add(nm);
					}
				}
				xpd.clausesPerTypes = clausesPerTypeList;
				return clausesPerTypeList;
			}
			
			private void addClause(IASTObject astObj, ClausesPerType clausePerType,
					ClauseBuilderContext cbContext) {
				if (astObj instanceof ConcatenateExpression) {
					ConcatenateExpression conc = (ConcatenateExpression)astObj;
					if (conc.getConcatenator() == Concatenator.BR_OPEN) {
						if (clausePerType.concatenator == null) {
							if (clausePerType.concat == null) {
								if (clausePerType.traversalClauses != null)
									initTraversalConcat(clausePerType);
								else
									clausePerType.concat = WHERE.BR_OPEN();
								clausePerType.previousOr = false;
							} else
								clausePerType.concat = clausePerType.concat.BR_OPEN();
						} else { // a predicate expression or a block close was before
							clausePerType.concat = clausePerType.concatenator.AND().BR_OPEN();
							clausePerType.concatenator = null;
							clausePerType.previousOr = false;
						}
						if (clausePerType.testForNextIsOr)
							clausePerType.valid = false;
					} else if (conc.getConcatenator() == Concatenator.BR_CLOSE) {
						if (clausePerType.concatenator == null) {
							throw new RuntimeException("illegal statement: " + conc.getConcatenator().name());
						} else {
							clausePerType.concatenator = clausePerType.concatenator.BR_CLOSE();
						}
						clausePerType.previousOr = false;
					} else if (conc.getConcatenator() == Concatenator.OR) {
						if (clausePerType.concatenator == null) {
							throw new RuntimeException("illegal statement: " + conc.getConcatenator().name());
						} else {
							clausePerType.concat = clausePerType.concatenator.OR();
							clausePerType.previousOr = true;
							clausePerType.testForNextIsOr = false; // or has occurred, we don't need further testing
							clausePerType.concatenator = null;
						}
					}
				} else if (astObj instanceof PredicateExpression) {
					if (clausePerType.testForNextIsOr) {
						clausePerType.valid = false;
						return;
					}
					PredicateExpression pred = (PredicateExpression)astObj;
					if (clausePerType.traversalClauses != null) {
						if (clausePerType.concat == null)
							initTraversalConcat(clausePerType);
					}
					Concat concat_1 = clausePerType.concat;
					if (clausePerType.concatenator != null) {
						concat_1 = clausePerType.concatenator.AND();
						clausePerType.previousOr = false;
					}
					clausePerType.concatenator = buildPredicateExpression(pred, concat_1, clausePerType, cbContext);
				} else if (astObj instanceof TraversalExpression) {
					clausePerType.traversalClauses = createTraversalClauses(clausePerType,
							(TraversalExpression) astObj, cbContext, false).getAllClauses(); // no copy with path
					List<DomainObjectMatch<?>> collXOwner =
							APIAccess.getCollectExpressionOwner(((TraversalExpression)astObj).getEnd());
					if (collXOwner != null) {
						// we need to produce copies of the traversal expression
						// for each collection expression which uses this traversal expression,
						// so that the result of the original traversal expression
						// will not be altered by the collection expression(s)
						for (DomainObjectMatch<?> dom : collXOwner) {
							String nnm = ValueAccess.getName(clausePerType.node).concat(collNodePostPrefix)
									.concat(APIAccess.getBaseNodeName(dom));
							JcNode n = new JcNode(nnm);
							ClausesPerType tempCpt =
									new ClausesPerType(n,
											clausePerType.domainObjectMatch,
											clausePerType.domainObjectType);
							TraversalResult travResult = createTraversalClauses(tempCpt,
									(TraversalExpression) astObj, cbContext, true); // copy with path
							cbContext.addTravClausesPerColl(dom,
									clausePerType.domainObjectType, travResult);
						}
					}
				} else if (astObj instanceof SelectExpression<?>) {
					List<IClause> selCls = createSelectClauses(clausePerType,
							(SelectExpression<?>) astObj, cbContext);
					if (selCls != null)
						clausePerType.selectClauses = selCls;
					else
						clausePerType.valid = false;
				}
			}
			
			private void initTraversalConcat(ClausesPerType cpt) {
				IClause cl = cpt.traversalClauses.get(
						cpt.traversalClauses.size() - 1);
				if (cl instanceof iot.jcypher.query.api.predicate.Concatenator) {
					cpt.concat = ((iot.jcypher.query.api.predicate.Concatenator)cl)
							.AND().BR_OPEN();
					cpt.closeBracket = true;
				}
			}
			
			@SuppressWarnings("unchecked")
			private iot.jcypher.query.api.predicate.Concatenator buildPredicateExpression(PredicateExpression pred,
					Concat concat, ClausesPerType clausesPerType, ClauseBuilderContext cbContext) {
				iot.jcypher.query.api.predicate.Concatenator ret = null;
				// handle negations
				int neg = pred.getNegationCount();
				Concat concat_1 = null;
				BooleanOperation booleanOp;
				Operator op = pred.getOperator();
				ValueElement val_1 = null;
				IPredicateOperand1 v_1 = pred.getValue_1();
				
				testValidInOperation(op, 1, pred); // throws an exception if not valid
				if (v_1 instanceof ValueElement)
					val_1 = testAndCloneIfNeeded((ValueElement)v_1, clausesPerType);
				else if (v_1 instanceof DomainObjectMatch<?>)
					val_1 = clausesPerType.node;
				
				if (val_1 != null) { // if either really null or invalid
					Object val_2 = pred.getValue_2();
					if (val_2 instanceof Parameter)
						val_2 = ((Parameter)val_2).getValue();
					testValidInOperation(op, 2, pred); // throws an exception if not valid
					
					boolean val2IsDom = false;
					List<Object> val_2s = null;
					if (val_2 instanceof IPredicateOperand1) { // ValueElement or DomainObjectMatch
						if (val_2 instanceof DomainObjectMatch<?>) { // after test op must be IN or EQUALS
							val2IsDom = true;
							val_2s = new ArrayList<Object>();
							val_2s.add(collectNodes((DomainObjectMatch<?>)val_2, cbContext.validNodes));
						} else
							val_2s = buildAllInstances((ValueElement)val_2, cbContext.validNodes);
					} else if (val_2 != null) {
						val_2s = new ArrayList<Object>();
						val_2s.add(val_2);
					}
					int cnt = val_2s != null ? val_2s.size() : 1;
					
					boolean negate = false;
					if (val2IsDom) { // after test op must be IN or EQUALS
						if (neg > 0) {
							neg--;
							negate = true;
						}
					}
					for (int i = neg; neg > 0; neg--) {
						if (i == neg) { // the first negation
							if (concat == null)
								concat_1 = (Concat)WHERE.NOT();
							else
								concat_1 = (Concat)concat.NOT();
						} else
							concat_1 = (Concat)concat_1.NOT();
					}
					if (concat_1 == null)
						concat_1 = concat;
					
					if (cnt > 1 || val2IsDom) { // encapsulate by brackets
						if (concat_1 != null)
							concat_1 = concat_1.BR_OPEN();
						else { // no negation(s)
							concat_1 = WHERE.BR_OPEN();
						}
					}
					
					if (!val2IsDom) {
						if (concat_1 == null)
							booleanOp = WHERE.valueOf(val_1);
						else
							booleanOp = concat_1.valueOf(val_1);
					} else
						booleanOp = null;
					
					for (int i = 0; i < cnt; i++) {
						if (val_2s != null)
							val_2 = val_2s.get(i);
						if (op == Operator.EQUALS)
							ret = booleanOp.EQUALS(val_2);
						else if (op == Operator.GT)
							ret = booleanOp.GT(val_2);
						else if (op == Operator.GTE)
							ret = booleanOp.GTE(val_2);
						else if (op == Operator.IN) {
							if (val2IsDom)
								ret = createWhereIn(concat_1, val_1, (List<JcNode>) val_2, negate);
							else
								ret = booleanOp.IN_list(val_2);
						} else if (op == Operator.CONTAINS) {
							// must be in collection expression
							// validity test has already been done
							if (pred.getValue_1() instanceof DomainObjectMatch<?>) {
								
							}
						} else if (op == Operator.IS_NULL)
							ret = booleanOp.IS_NULL();
						else if (op == Operator.LIKE)
							ret = booleanOp.REGEX(val_2.toString());
						else if (op == Operator.LT)
							ret = booleanOp.LT(val_2);
						else if (op == Operator.LTE)
							ret = booleanOp.LTE(val_2);
						
						if (i < cnt - 1)
							booleanOp = ret.OR().valueOf(val_1);
					}
					if (cnt > 1) { // encapsulate by brackets
						ret = ret.BR_CLOSE();
					}
				} else
					clausesPerType.valid = false;
				
				return ret;
			}
			
			/**
			 * return true if a WITH clause is to be added because an order expression is required
			 * @param cpt
			 * @param withClauses
			 * @return true if a WITH clause is to be added because an order expression is required
			 */
			private boolean buildAddWithClause(ClausesPerType cpt,
					List<IClause> withClauses) {
				boolean needWith = false;
				// add to the WITH clause to be used in the next query part
				RSortable withClause = WITH.value(cpt.node);
				List<OrderBy> ocs = getOrderExpressionsFor(cpt);
				if (ocs != null) { // if != null it contains at least one criteria
					needWith = true;
					for (OrderBy ob : ocs) {
						if (ob.getDirection() == 0)
							withClause = withClause.ORDER_BY(ob.getAttributeName()); // TODO use property name
						else
							withClause = withClause.ORDER_BY_DESC(ob.getAttributeName());
					}
				}
				withClauses.add(withClause);
				return needWith;
			}
			
			private iot.jcypher.query.api.predicate.Concatenator createWhereIn(
					Concat concat, ValueElement val_1, List<JcNode> val_2, boolean not) {
				iot.jcypher.query.api.predicate.Concatenator booleanOp = null;
				Concat concat_1 = concat;
				int idx = 0;
				for (JcNode n : val_2) {
					if (idx == 0 && val_2.size() > 1) // encapsulate with brackets
						concat_1 = concat_1.BR_OPEN();
					if (not) {
						if (idx == 0)
							booleanOp = concat_1.valueOf(n).IS_NULL().OR().NOT().valueOf(val_1).IN_list(n).BR_CLOSE();
						else
							booleanOp = booleanOp.AND().BR_OPEN().valueOf(n).IS_NULL().OR().NOT().valueOf(val_1).IN_list(n).BR_CLOSE();
					} else {
						if (idx == 0)
							booleanOp = concat_1.NOT().valueOf(n).IS_NULL().AND().valueOf(val_1).IN_list(n).BR_CLOSE();
						else
							booleanOp.OR().BR_OPEN().NOT().valueOf(n).IS_NULL().AND().valueOf(val_1).IN_list(n).BR_CLOSE();
					}
					if ((idx == val_2.size() - 1) && val_2.size() > 1) // encapsulate with brackets
						booleanOp = booleanOp.BR_CLOSE();
					idx++;
				}
				return booleanOp;
			}
			
			private TraversalResult createTraversalClauses(ClausesPerType cpt, TraversalExpression travEx,
					ClauseBuilderContext cbContext, boolean copy) {
				TraversalResult ret = new TraversalResult();
				ret.domainObjectType = cpt.domainObjectType;
				ret.expressionParts = new ArrayList<List<IClause>>();
				String startBName = APIAccess.getBaseNodeName(travEx.getStart());
				String origEndNodeName = ValueAccess.getName(cpt.node);
				String endNodeLabel = getMappingInfo().getLabelForClass(cpt.domainObjectType);
				
				List<StepClause> stepCls = new ArrayList<StepClause>();
				for (String validNodeName : cbContext.validNodes) {
					if (validNodeName.indexOf(startBName) == 0) {
						int tmpNodeIdx = cbContext.stepClausesCount + stepCls.size();
						// only valid clauses are returned
						List<StepClause> partStepCls = buildStepClauses(travEx, tmpNodeIdx, validNodeName,
								endNodeLabel, origEndNodeName, copy);
						List<IClause> part = new ArrayList<IClause>();
						for (StepClause sc : partStepCls) {
							part.add(SEPARATE.nextClause());
							part.add(sc.getTotalMatchNode());
						}
						ret.expressionParts.add(part);
						stepCls.addAll(partStepCls);
					}
				}
				cbContext.stepClausesCount = cbContext.stepClausesCount + stepCls.size();
				
				if (stepCls.size() == 1) { // don't need to build union of multiple sets
					StepClause sc = stepCls.get(0);
					String nnm = ValueAccess.getName(cpt.node);
					JcNode endNd = sc.getEndNode();
					ValueAccess.setName(nnm,
							endNd);
					if (sc.jcPath != null) {
						ValueAccess.setName(sc.pathFromNode(nnm), sc.jcPath);
						ret.getStartPaths().add(sc.jcPath);
						ret.getEndNodes().add(endNd);
					}
				} else if (stepCls.size() > 1) {
					List<JcNode> tempNodes = new ArrayList<JcNode>();
					for (StepClause sc : stepCls) {
						JcNode endNd = sc.getEndNode();
						tempNodes.add(endNd);
						if (sc.jcPath != null) {
							ValueAccess.setName(sc.pathFromNode(
									ValueAccess.getName(sc.getEndNode())), sc.jcPath);
							ret.getStartPaths().add(sc.jcPath);
							ret.getEndNodes().add(endNd);
						}
					}
					if (!copy) { // in this case the collection expression using
										// the copy works on each part-path in turn
										// therefore  we don't need to build the union
						List<IClause> part = new ArrayList<IClause>();
						part.add(SEPARATE.nextClause());
						part.add(OPTIONAL_MATCH.node(cpt.node).label(endNodeLabel));
						Concat concat = WHERE.BR_OPEN();
						part.add(createWhereIn(concat, cpt.node, tempNodes, false));
						ret.expressionParts.add(part);
					}
				} else {
					cpt.valid = false;
//					throw new RuntimeException("attribute(s): " + invalidAttribs + " do(es) not exist " +
//							"in domain object type(s): " + inValidTypes);
				}
				return ret;
			}
			
			private List<IClause> createSelectClauses(ClausesPerType cpt, SelectExpression<?> selEx,
					ClauseBuilderContext cbContext) {
				// the select can return results only for valid nodes in start set,
				// because it selects from the start set.
				if (!cbContext.isNodeValid(APIAccess.getNodeForType(selEx.getStart(), cpt.domainObjectType)))
					return null;
				
				List<IClause> ret = new ArrayList<IClause>();
				
				// get the DOMs which are derived by traversal and which occur
				// as start in a predicate expression within this collection expression.
				// The appropriate traversal expressions have been cloned, so that
				// the predicate expression does not constrain the result of the original
				// traversal expression.
				List<DomainObjectMatch<?>> travDOMs = selEx.getTraversalResults();
				Map<IASTObject, TraversalPathsPerDOM> astObject2TraversalPaths =
						new HashMap<IASTObject, TraversalPathsPerDOM>();
				if (travDOMs != null) {
					for (DomainObjectMatch<?> dom : travDOMs) {
						int idx = 0;
						ExpressionsPerDOM xpd = null;
						TraversalPathsPerDOM tpd = new TraversalPathsPerDOM();
						// for all types of the DomainObjectMatch
						List<Class<?>> typeList = APIAccess.getTypeList(dom);
						for (int i = 0; i < typeList.size(); i++) {
							if (cbContext.isNodeValid(APIAccess.getNodes(dom).get(i))) {
								Class<?> clazz = typeList.get(i);
								// The TraversalResult contains the cloned traversal clauses
								// (cloned for this collection expression)
								TraversalResult travResult = cbContext.getTravResult(cpt.domainObjectMatch, clazz);
								
								// add a traversal expression clone only once for each collection expression
								if (travResult.expressionsPerDOM == null) {
									// find all expressions within the collection expression
									// which act on the DomainObjectmatch dom.
									if (xpd == null)
										xpd = buildExpressionsPerDOM(dom, selEx.getAstObjects());
									// also serves as marker, that the teraversal expression clone has been added
									travResult.expressionsPerDOM = xpd; 
									
									tpd.paths.addAll(travResult.getStartPaths());
									int idx2 = 0;
									for (List<IClause> part : travResult.expressionParts) {
										if (!part.isEmpty()) {
											// TODO is number of end nodes always equal to number of parts?
											JcNode nd = travResult.getEndNodes().get(idx2);
											ClausesPerType intCpt = new ClausesPerType(nd, dom,
													travResult.domainObjectType);
											intCpt.concat = WHERE.BR_OPEN();
											for (IASTObject ao : xpd.xPressions) {
												if (ao instanceof PredicateExpression) {
													PredicateExpression pred = (PredicateExpression)ao;
													IPredicateOperand1 val1 = pred.getValue_1();
													if (val1 instanceof ValueElement) {
														ValueElement ve = (ValueElement)val1;
														ve = cloneVe(ve, ValueAccess.findFirst(ve), nd);
														pred.setValue_1(ve);
													}
												}
												addClause(ao, intCpt, cbContext);
												if (idx == 0)
													astObject2TraversalPaths.put(ao, tpd);
											}
											idx++;
											idx2++;
											ret.addAll(part);
											ret.add(intCpt.concatenator);
											ret.add(SEPARATE.nextClause());
										}
									}
								} else { // mark the IASTObjects which have already been processed
									tpd.paths.addAll(travResult.getStartPaths());
									for (IASTObject ao : travResult.expressionsPerDOM.xPressions) {
										astObject2TraversalPaths.put(ao, tpd);
									}
								}
							}
						}
					}
				}
				
				String nodeLabel = getMappingInfo().getLabelForClass(cpt.domainObjectType);
				ret.add(
					OPTIONAL_MATCH.node(cpt.node).label(nodeLabel)
				);
				Concat concat = WHERE.BR_OPEN();
				List<JcNode> nds = cbContext.filterValidNodes(APIAccess.getNodes(selEx.getStart()));
				iot.jcypher.query.api.predicate.Concatenator concatenator = createWhereIn(concat, cpt.node, nds, false);
				ClausesPerType tempCpt = new ClausesPerType(cpt.node, cpt.domainObjectMatch, cpt.domainObjectType);
				tempCpt.concatenator = concatenator;
				cbContext.stepClausesCount = 0;

				if (selEx.getAstObjects().size() > 0)
					addClause(new ConcatenateExpression(Concatenator.BR_OPEN), tempCpt, cbContext);
				
				for (IASTObject ao : selEx.getAstObjects()) {
					TraversalPathsPerDOM tpd = astObject2TraversalPaths.get(ao);
					if (tpd != null) { // constraint defined by a traversal expression
						if (!tpd.consumed) {
							tpd.consumed = true;
							// add clauses for the traversals (match path start)
							addTraversalConstraints2Select(tpd.paths, tempCpt);
						}
					} else {
						addClause(ao, tempCpt, cbContext);
						if(!tempCpt.valid)
							break;
					}
				}
				
				
				if (tempCpt.valid && selEx.getAstObjects().size() > 0)
					addClause(new ConcatenateExpression(Concatenator.BR_CLOSE), tempCpt, cbContext);
				
				if (tempCpt.concatenator != null)
					ret.add(tempCpt.concatenator);
				else
					ret.add(concatenator);
				
				return ret;
			}
			
			private void addTraversalConstraints2Select(List<JcPath> paths, ClausesPerType cpt) {
				if (paths.size() > 0) {
					boolean bracket = false;
					if (cpt.concatenator != null) {
						cpt.concat = cpt.concatenator.AND().BR_OPEN();
						bracket = true;
					}
					int idx = 0;
					for(JcPath path : paths) {
						if (idx > 0)
							cpt.concatenator =
									cpt.concatenator.OR().valueOf(cpt.node).EQUALS(path.nodes().head());
						else
							cpt.concatenator =
								cpt.concat.valueOf(cpt.node).EQUALS(path.nodes().head());
						idx++;
					}
					if (bracket)
						cpt.concatenator =
								cpt.concatenator.BR_CLOSE();
				}
			}
			
			private List<StepClause> buildStepClauses(TraversalExpression travEx, int tmpNodeIdx, String startNodeName,
					String endNodeLabel, String origEndNodeName, boolean copy) {
				StepClause stepClause = new StepClause();
				stepClause.buildAll(travEx, tmpNodeIdx, startNodeName, endNodeLabel,
						origEndNodeName, copy);
				
				List<StepClause> validClauses = new ArrayList<StepClause>();
				for (StepClause sc : stepClause.stepClauses) {
					if (sc.getTotalMatchNode() != null)
						validClauses.add(sc);
				}
				
				return validClauses;
			}
			
			/***************************/
			private class TraversalPathsPerDOM {
				private List<JcPath> paths;
				private boolean consumed;
				
				private TraversalPathsPerDOM() {
					super();
					this.paths = new ArrayList<JcPath>();
					this.consumed = false;
				}
			}
			
			/***************************/
			private class TraversalResult {
				private List<List<IClause>> expressionParts;
				private List<JcPath> startPaths;
				private List<JcNode> endNodes;
				private Class<?> domainObjectType;
				private ExpressionsPerDOM expressionsPerDOM;
				
				private List<IClause> getAllClauses() {
					List<IClause> ret = new ArrayList<IClause>();
					for (List<IClause> cls : this.expressionParts) {
						ret.addAll(cls);
					}
					return ret;
				}
				
				private List<JcPath> getStartPaths() {
					if (this.startPaths == null)
						this.startPaths = new ArrayList<JcPath>();
					return this.startPaths;
				}
				
				private List<JcNode> getEndNodes() {
					if (this.endNodes == null)
						this.endNodes = new ArrayList<JcNode>();
					return this.endNodes;
				}
			}
			
			/***************************/
			private class StepClause {
				private Node matchNode;
				private StepClause next;
				private StepClause previous;
				private FieldMapping fieldMapping;
				private List<FieldMapping> fieldMappings;
				private int fmIndex;
				private TraversalExpression traversalExpression;
				private Step step;
				private int stepIndex;
				private String originalEndNodeName;
				private String endNodeLabel;
				private JcPath jcPath;
				private JcNode startNode;
				private JcNode endNode;
				private List<StepClause> stepClauses;
				
				private void buildAll(TraversalExpression travEx, int tmpNodeIdx, String startNodeName, String endNodeLabel,
						String origEndNodeName, boolean copy) {
					this.stepClauses = new ArrayList<StepClause>();
					this.originalEndNodeName = origEndNodeName;
					this.endNodeLabel = endNodeLabel;
					this.traversalExpression = travEx;
					this.buildFirst(tmpNodeIdx, startNodeName, copy);
				}
				
				private void buildFirst(int tmpNodeIdx, String startNodeName, boolean copy) {
					this.stepClauses.add(this);
					this.startNode = new JcNode(startNodeName);
					if (copy) {
						this.jcPath = new JcPath(pathFromNode(this.originalEndNodeName));
						this.matchNode = OPTIONAL_MATCH.path(this.jcPath).node(this.startNode);
					} else
						this.matchNode = OPTIONAL_MATCH.node(this.startNode);
					Class<?> typ = APIAccess.getTypeForNodeName(this.traversalExpression.getStart(), startNodeName);
					boolean isList = typ.equals(Collection.class) ||
							typ.equals(Array.class); // TODO what about other surrogates
					this.stepIndex = 0;
					this.step = this.traversalExpression.getSteps().get(this.stepIndex);
					List<Class<?>> types = new ArrayList<Class<?>>();
					types.add(typ);
					this.build(tmpNodeIdx, types, isList);
				}
				
				private void buildNext(int tmpNodeIdx, List<Class<?>> types, boolean isList,
						Step nextStep, int nextStepIndex) {
					StepClause stpc = new StepClause();
					this.next = stpc;
					stpc.previous = this;

					stpc.matchNode = this.matchNode;
					stpc.endNodeLabel = this.endNodeLabel;
					stpc.originalEndNodeName = this.originalEndNodeName;
					stpc.traversalExpression = this.traversalExpression;
					stpc.step = nextStep;
					stpc.stepClauses = this.stepClauses;
					stpc.stepIndex = nextStepIndex;
					stpc.build(tmpNodeIdx, types, isList);
				}
				
				private void buildNextClone(int tmpNodeIdx, List<Class<?>> types, boolean isList,
						CloneInfo cloneInfo) {
					cloneInfo.toClone = cloneInfo.toClone.next;
					StepClause stpc = new StepClause();
					this.next = stpc;
					stpc.previous = this;

					stpc.matchNode = this.matchNode;
					stpc.endNodeLabel = this.endNodeLabel;
					stpc.originalEndNodeName = this.originalEndNodeName;
					stpc.traversalExpression = this.traversalExpression;
					stpc.stepClauses = this.stepClauses;
					stpc.stepIndex = cloneInfo.toClone.stepIndex;
					stpc.step = cloneInfo.toClone.step;
					stpc.fieldMapping = cloneInfo.toClone.fieldMapping;
					stpc.fieldMappings = cloneInfo.toClone.fieldMappings;
					stpc.fmIndex = cloneInfo.toClone.fmIndex;
					stpc.buildClone(tmpNodeIdx, cloneInfo, isList);
				}
				
				private void build(int tmpNodeIdx, List<Class<?>> types, boolean isList) {
					FieldMapping fm = null;
					List<FieldMapping> fms = null;
					for (Class<?> t : types) {
						if (this.step.getDirection() == 0) { // forward
							fm = getMappingInfo().getFieldMapping(this.step.getAttributeName(),
									t);
							if (fm != null)
								break;
						} else { // backward
							if (fms == null)
								fms = new ArrayList<FieldMapping>();
							List<FieldMapping> fms_t = getMappingInfo().getBackwardFieldMappings(this.step.getAttributeName(),
									t);
							for (FieldMapping f : fms_t) {
								if (!fms.contains(f))
									fms.add(f);
							}
						}
					}
					
					boolean doBuild = false;
					if (fms != null && !fms.isEmpty()) {
						if (fms.size() == 1) // to optimize
							this.fieldMapping = fms.get(0);
						else {
							this.fieldMappings = fms;
							this.fmIndex = 0;
						}
						doBuild = true;
					} else {
						if (fm != null) {
							this.fieldMapping = fm;
							doBuild = true;
						} else // navigation has no result
							this.matchNode = null;
					}
					
					if (doBuild) {
						if (this.step.getDirection() == 0) // forward
							this.buildForwardStep(tmpNodeIdx, isList, null);
						else
							this.buildBackwardStep(tmpNodeIdx, null);
					}
					
					// test for additional paths
					if (this.fieldMappings != null && this.fmIndex < this.fieldMappings.size() - 1) {
						this.cloneTraversal(this, tmpNodeIdx + 1);
					}
				}
				
				private void buildForwardStep(int tmpNodeIdx, boolean listOrArray, CloneInfo cloneInfo) {
					Relation matchRel = matchNode.relation().type(this.fieldMapping.getPropertyOrRelationName());
					matchRel = matchRel.out();
					
					if (this.step.getMinDistance() != 1)
						matchRel = matchRel.minHops(this.step.getMinDistance());
					if (this.step.getMaxDistance() != 1) {
						if (this.step.getMaxDistance() == -1)
							matchRel = matchRel.maxHopsUnbound();
						else
							matchRel = matchRel.maxHops(this.step.getMaxDistance());
					}
					
					CompoundObjectType cType;
					if (listOrArray) {
						cType = getMappingInfo().getInternalDomainAccess()
								.getFieldComponentType(this.fieldMapping.getClassFieldName());
					} else {
						cType = getMappingInfo().getInternalDomainAccess()
								.getConcreteFieldType(this.fieldMapping.getClassFieldName());
					}
					List<Class<?>> types;
					Class<?> typ = cType.getType();
					boolean isList = typ.equals(Collection.class) ||
							typ.equals(Array.class); // TODO alternative check for surrogate
					Step nextStep = null;
					int nextStepIndex = this.stepIndex;
					if (isList) { // need to advance one step
						// surrogates have one (non-transient) field
						nextStep = this.step.createStep(this.step.getDirection(),
								getMappingInfo().getObjectMappingFor(typ)
									.fieldMappingsIterator().next().getPropertyOrRelationName());
						types = new ArrayList<Class<?>>();
						types.add(typ);
					} else {
						nextStepIndex++;
						if (nextStepIndex <= this.traversalExpression.getSteps().size() - 1)
							nextStep = this.traversalExpression.getSteps().get(nextStepIndex);
						types = cType.getTypes(true);
					}
					
					if (nextStep == null) { // we have reached the end
						if (isValidEndNodeType(types)) {
							this.endNode = new JcNode(this.originalEndNodeName.concat(tmpNodePostPrefix)
									.concat(String.valueOf(tmpNodeIdx)));
							StepClause first = this.getFirst();
							if (first.jcPath != null) {
								String npm = ValueAccess.getName(first.jcPath).concat(tmpNodePostPrefix)
										.concat(String.valueOf(tmpNodeIdx));
								ValueAccess.setName(npm, first.jcPath);
							}
							this.matchNode = matchRel.node(this.endNode).label(this.endNodeLabel);
						} else
							this.matchNode = null;
					} else {
						this.matchNode = matchRel.node();
						if (cloneInfo != null)
							this.buildNextClone(tmpNodeIdx, types, isList, cloneInfo);
						else
							this.buildNext(tmpNodeIdx, types, isList, nextStep, nextStepIndex);
					}
				}
				
				private void buildBackwardStep(int tmpNodeIdx, CloneInfo cloneInfo) {
					FieldMapping fm;
					if (this.fieldMappings != null) // there are multiple navigation paths
						fm = this.fieldMappings.get(this.fmIndex);
					else
						fm = this.fieldMapping;

					Relation matchRel = matchNode.relation().type(fm.getPropertyOrRelationName());
					matchRel = matchRel.in();
					
					if (this.step.getMinDistance() != 1)
						matchRel = matchRel.minHops(this.step.getMinDistance());
					if (this.step.getMaxDistance() != 1) {
						if (this.step.getMaxDistance() == -1)
							matchRel = matchRel.maxHopsUnbound();
						else
							matchRel = matchRel.maxHops(this.step.getMaxDistance());
					}
					
					Class<?> typ = fm.getField().getDeclaringClass();
					boolean isList = typ.equals(Collection.class) ||
							typ.equals(Array.class); // TODO alternative check for surrogate
					
					List<Class<?>> types;
					Step nextStep = null;
					int nextStepIndex = this.stepIndex;
					if (isList) { // need to advance one step
						// surrogates have one (non-transient) field
						nextStep = this.step.createStep(this.step.getDirection(),
								this.step.getAttributeName());
						types = new ArrayList<Class<?>>();
						types.add(typ);
					} else {
						nextStepIndex++;
						if (nextStepIndex <= this.traversalExpression.getSteps().size() - 1)
							nextStep = this.traversalExpression.getSteps().get(nextStepIndex);
						types = getMappingInfo().getCompoundTypesFor(typ);
					}
					
					if (nextStep == null) { // we have reached the end
						if (isValidEndNodeType(types)) {
							this.endNode = new JcNode(this.originalEndNodeName.concat(tmpNodePostPrefix)
									.concat(String.valueOf(tmpNodeIdx)));
							StepClause first = this.getFirst();
							if (first.jcPath != null) {
								String npm = ValueAccess.getName(first.jcPath).concat(tmpNodePostPrefix)
										.concat(String.valueOf(tmpNodeIdx));
								ValueAccess.setName(npm, first.jcPath);
							}
							this.matchNode = matchRel.node(this.endNode).label(this.endNodeLabel);
						} else
							this.matchNode = null;
					} else {
						this.matchNode = matchRel.node();
						if (cloneInfo != null)
							this.buildNextClone(tmpNodeIdx, types, isList, cloneInfo);
						else
							this.buildNext(tmpNodeIdx, types, isList, nextStep, nextStepIndex);
					}
				}
				
				private boolean isValidEndNodeType(List<Class<?>> types) {
					for (Class<?> clazz : types) {
						if (this.endNodeLabel.equals(getMappingInfo().getLabelForClass(clazz)))
							return true;
					}
					return false;
				}

				private void cloneTraversal(StepClause cloneTo, int tmpNodeIdx) {
					StepClause first = this.getFirst();
					StepClause newFirst = new StepClause();
					newFirst.stepClauses = first.stepClauses;
					CloneInfo cloneInfo = new CloneInfo();
					cloneInfo.toClone = first;
					cloneInfo.cloneTo = cloneTo;
					newFirst.cloneFirst(cloneInfo, tmpNodeIdx);
				}
				
				private void cloneFirst(CloneInfo cloneInfo, int tmpNodeIdx) {
					this.stepClauses.add(this);
					this.startNode = cloneInfo.toClone.startNode;
					this.jcPath = cloneInfo.toClone.jcPath;
					this.originalEndNodeName = cloneInfo.toClone.originalEndNodeName;
					this.endNodeLabel = cloneInfo.toClone.endNodeLabel;
					this.traversalExpression = cloneInfo.toClone.traversalExpression;
					if (this.jcPath != null)
						this.matchNode = OPTIONAL_MATCH.path(this.jcPath).node(this.startNode);
					else
						this.matchNode = OPTIONAL_MATCH.node(this.startNode);
					Class<?> typ = APIAccess.getTypeForNodeName(this.traversalExpression.getStart(),
							ValueAccess.getName(this.startNode));
					boolean isList = typ.equals(Collection.class) ||
							typ.equals(Array.class); // TODO what about other surrogates
					this.stepIndex = cloneInfo.toClone.stepIndex;
					this.step = cloneInfo.toClone.step;
					this.fieldMapping = cloneInfo.toClone.fieldMapping;
					this.fieldMappings = cloneInfo.toClone.fieldMappings;
					this.fmIndex = cloneInfo.toClone.fmIndex;
					this.buildClone(tmpNodeIdx, cloneInfo, isList);
				}
				
				private void buildClone(int tmpNodeIdx, CloneInfo cloneInfo, boolean isList) {
					CloneInfo cli = cloneInfo;
					if (cli.stopCloning()) {
						this.fmIndex++;
						cli = null;
					}
					
					if (this.step.getDirection() == 0) // forward
						this.buildForwardStep(tmpNodeIdx, isList, cli);
					else
						this.buildBackwardStep(tmpNodeIdx, cli);
					
					// test for additional paths
					if (this.fieldMappings != null && this.fmIndex < this.fieldMappings.size() - 1) {
						this.cloneTraversal(this, tmpNodeIdx + 1);
					}
				}

				private JcNode getEndNode() {
					return this.getLast().endNode;
				}
				
				private Node getTotalMatchNode() {
					return this.getLast().matchNode;
				}
				
				private StepClause getFirst() {
					StepClause first = this;
					while(first.previous != null) {
						first = first.previous;
					}
					return first;
				}
				
				private StepClause getLast() {
					StepClause last = this;
					while(last.next != null) {
						last = last.next;
					}
					return last;
				}
				
				private String pathFromNode(String nodeName) {
					return nodeName.replaceFirst(APIAccess.nodePrefix, APIAccess.pathPrefix);
				}
			}
			
			/***********************************/
			private class CloneInfo {
				private StepClause toClone;
				private StepClause cloneTo;
				
				private boolean stopCloning() {
					return this.toClone == this.cloneTo;
				}
			}
		}
		
		/*************************************/
		private class ClauseBuilderContext {
			private int stepClausesCount = 0;
			private List<String> validNodes;
			
			// The DomainObjectMatch as Map-Key is owner of the collection expression;
			// the one which is produced by the collection expression.
			// The Class which is key in the inner map is one type of the DomainObjectMatch which is owner
			// of the tarversal expression (produced by the traversal expression)
			private Map<DomainObjectMatch<?>,
				Map<Class<?>, ClauseBuilder.TraversalResult>> travClausesPerCollectXpr;
			
			private ClauseBuilderContext(List<String> validNodes) {
				super();
				this.validNodes = validNodes;
			}

			private void addTravClausesPerColl(DomainObjectMatch<?> collOwner,
					Class<?> travOwner, ClauseBuilder.TraversalResult travResult) {
				if (this.travClausesPerCollectXpr == null)
					this.travClausesPerCollectXpr = new HashMap<DomainObjectMatch<?>,
							Map<Class<?>, ClauseBuilder.TraversalResult>>();
				Map<Class<?>, ClauseBuilder.TraversalResult> travClauseMap =
						this.travClausesPerCollectXpr.get(collOwner);
				if (travClauseMap == null) {
					travClauseMap = new HashMap<Class<?>, ClauseBuilder.TraversalResult>();
					this.travClausesPerCollectXpr.put(collOwner, travClauseMap);
				}
				travClauseMap.put(travOwner, travResult);
			}
			
			private ClauseBuilder.TraversalResult getTravResult(DomainObjectMatch<?> collOwner,
					Class<?> travOwner) {
				return this.travClausesPerCollectXpr.get(collOwner).get(travOwner);
			}
			
			private boolean isNodeValid(JcNode nd) {
				String ndName = ValueAccess.getName(nd);
				for (String n : this.validNodes) {
					if (n.equals(ndName))
						return true;
				}
				return false;
			}
			
			private List<JcNode> filterValidNodes(List<JcNode> nds) {
				List<JcNode> ret = new ArrayList<JcNode>();
				for (JcNode n : nds) {
					if (this.isNodeValid(n))
						ret.add(n);
				}
				return ret;
			}
		}
		
		/*************************************/
		private class StateContext {
			private State state;
			private int blockCount;
			private String baseNodeName;
			private List<IASTObject> candidates;
			private List<String> dependencies;
			
			// -1 .. init,
			// 0 .. foreign predicate expression,
			private int orRemoveState;
			private IASTObject orToAdd;
			
			private StateContext(String bndNm) {
				super();
				this.state = State.INIT;
				this.blockCount = 0;
				this.candidates = new ArrayList<IASTObject>();
				this.orRemoveState = -1;
				this.baseNodeName = bndNm;
			}
			
			private void addDependency(String nodeName) {
				if (nodeName != null) {
					String nn = nodeName;
					if (this.dependencies == null)
						this.dependencies = new ArrayList<String>();
					int idx = nodeName.indexOf(separator);
					idx = nodeName.indexOf(separator, idx + 1);
					// extract base-node-name if needed
					if (idx >= 0)
						nn = nodeName.substring(0, idx);
					if (!nn.equals(this.baseNodeName) && !this.dependencies.contains(nn))
						this.dependencies.add(nn);
				}
			}
		}
		
		/*************************************/
		private class ClausesPerType {
			private DomainObjectMatch<?> domainObjectMatch;
			private JcNode node;
			private Class<?> domainObjectType;
			private boolean valid;
			private boolean previousOr;
			private boolean testForNextIsOr;
			private int pageOffset;
			private int pageLength;
			private List<IClause> clauses;
			private ExpressionsPerDOM expressionsPerDOM;
			private Concat concat = null;
			private iot.jcypher.query.api.predicate.Concatenator concatenator = null;
			private List<IClause> traversalClauses;
			private List<IClause> selectClauses;
			private boolean closeBracket;
			
			private ClausesPerType(JcNode node, DomainObjectMatch<?> dom, Class<?> type) {
				super();
				this.node = node;
				this.domainObjectMatch = dom;
				this.domainObjectType = type;
				this.valid = true;
				this.previousOr = false;
				this.testForNextIsOr = false;
				this.closeBracket = false;
			}
			
			private boolean needSkipsLimits() {
				return this.pageOffset > 0 || this.pageLength >= 0;
			}
			
			private JcNumber getJcNumber(String prefix) {
				return QueryBuilder.this.getJcNumber(prefix, this.node);
			}
			
			/**
			 * may return null in case it is not valid
			 * @return
			 */
			private List<IClause> getClauses() {
				if (this.clauses == null) {
					if (this.valid) {
						this.clauses = new ArrayList<IClause>();
						if (this.traversalClauses != null) {
							if (this.concatenator != null) {
								IClause cl = this.traversalClauses.get(
										this.traversalClauses.size() - 1);
								if (cl instanceof iot.jcypher.query.api.predicate.Concatenator)
									this.traversalClauses.remove(this.traversalClauses.size() - 1);
							}
							this.clauses.addAll(this.traversalClauses);
							this.traversalClauses = null;
						} else if (this.selectClauses != null) {
							this.clauses.addAll(this.selectClauses);
						} else {
							String nodeLabel = getMappingInfo().getLabelForClass(this.domainObjectType);
							this.clauses.add(
								OPTIONAL_MATCH.node(this.node).label(nodeLabel)
							);
						}
						if (this.concatenator != null)
							this.clauses.add(this.closeBracket ? this.concatenator.BR_CLOSE() :
								this.concatenator);
						else
							this.clauses.add(SEPARATE.nextClause());
					}
				}
				return this.clauses;
			}
			
			private void addMatchClauses(List<IClause> clauses) {
				List<IClause> mcs = this.getClauses();
				if (mcs != null)
					clauses.addAll(mcs);
			}
			
			private void addDependencyClauses(List<IClause> clauses) {
				if (this.expressionsPerDOM.flattenedDependencies != null) {
					for (ExpressionsPerDOM xpd : this.expressionsPerDOM.flattenedDependencies) {
						for (ClausesPerType cpt : xpd.clausesPerTypes) {
							cpt.addMatchClauses(clauses);
						}
					}
				}
			}
		}
		
		/*************************************/
		private class ExpressionsPerDOM {
			private DomainObjectMatch<?> domainObjectMatch;
			private List<IASTObject> xPressions;
			private List<DomainObjectMatch<?>> dependencies;
			private List<ExpressionsPerDOM> flattenedDependencies;
			private List<ClausesPerType> clausesPerTypes;
			
			private ExpressionsPerDOM(DomainObjectMatch<?> domainObjectMatch,
					List<IASTObject> xPressions, List<String> deps) {
				super();
				this.domainObjectMatch = domainObjectMatch;
				this.xPressions = xPressions;
				if (deps != null) {
					this.dependencies = new ArrayList<DomainObjectMatch<?>>(deps.size());
					for (String nn : deps) {
						for (DomainObjectMatch<?> dom : domainObjectMatches) {
							if (APIAccess.getBaseNodeName(dom).equals(nn)) {
								this.dependencies.add(dom);
								break;
							}
						}
					}
				}
			}
			
			private void addDependencies(List<ExpressionsPerDOM> xpds) {
				if (xpds != null) {
					if (this.flattenedDependencies == null) {
						this.flattenedDependencies = new ArrayList<ExpressionsPerDOM>();
						this.flattenedDependencies.addAll(xpds);
					} else {
						for (ExpressionsPerDOM xpd : xpds) {
							if (!this.flattenedDependencies.contains(xpd))
								this.flattenedDependencies.add(xpd);
						}
					}
				}
			}
			
			private void addDependency(ExpressionsPerDOM xpd) {
				if (this.flattenedDependencies == null)
					this.flattenedDependencies = new ArrayList<ExpressionsPerDOM>();
				this.flattenedDependencies.add(xpd);
			}
		}
	}
	
	/************************************/
	private class QueryContext {
		private List<ResultsPerType> resultsPerType;
		private Map<DomainObjectMatch<?>, List<ResultsPerType>> resultsMap;
		private boolean execCount;

		private QueryContext(boolean execCount) {
			super();
			this.resultsPerType = new ArrayList<ResultsPerType>();
			this.resultsMap = new HashMap<DomainObjectMatch<?>, List<ResultsPerType>>();
			this.execCount = execCount;
		}
		
		private ResultsPerType addFor(QueryBuilder.ClausesPerType cpt, boolean isCountQuery) {
			DomainObjectMatch<?> dom = cpt.domainObjectMatch;
			Class<?> type = cpt.domainObjectType;
			List<ResultsPerType> resPerTypeList = this.resultsMap.get(dom);
			if (resPerTypeList == null) {
				resPerTypeList = new ArrayList<ResultsPerType>();
				this.resultsMap.put(dom, resPerTypeList);
			}
			ResultsPerType resPerType = null;
			for (ResultsPerType rpt : resPerTypeList) {
				if (rpt.type.equals(type)) {
					resPerType = rpt;
					break;
				}
			}
			if (resPerType == null) {
				String pref = isCountQuery ? countPrefix : idPrefix;
				resPerType = new ResultsPerType(dom, type, cpt.getJcNumber(pref));
				resPerTypeList.add(resPerType);
			}
			return resPerType;
		}
		
		private void addEmptyFor(QueryBuilder.ClausesPerType cpt) {
			DomainObjectMatch<?> dom = cpt.domainObjectMatch;
			List<ResultsPerType> resPerTypeList = this.resultsMap.get(dom);
			if (resPerTypeList == null) {
				resPerTypeList = new ArrayList<ResultsPerType>();
				this.resultsMap.put(dom, resPerTypeList);
			}
		}
		
		private void queryExecuted() {
			Iterator<Entry<DomainObjectMatch<?>, List<ResultsPerType>>> it = this.resultsMap.entrySet().iterator();
			while(it.hasNext()) {
				Entry<DomainObjectMatch<?>, List<ResultsPerType>> entry = it.next();
				APIAccess.setPageChanged(entry.getKey(), false);
			}
		}
		
		/************************************/
		private class ResultsPerType {
			private DomainObjectMatch<?> domainObjectMatch;
			private Class<?> type;
			private JcNumber jcNumber;
			private List<Long> ids;
			private long count;
			private int queryIndex;
			
			private ResultsPerType(DomainObjectMatch<?> dom, Class<?> type, JcNumber num) {
				super();
				this.domainObjectMatch = dom;
				this.type = type;
				this.jcNumber = num;
				this.count = 0;
				this.queryIndex = 0;
			}
		}
	}
	
	/*************************************/
	private enum State {
		INIT, HAS_XPRESSION
	}
	
	/************************************/
	public class MappingInfo {

		private MappingInfo() {
			super();
		}

		public ObjectMapping getObjectMappingFor(Class<?> domainObjectType) {
			return ((IIntDomainAccess)domainAccess).getInternalDomainAccess()
					.getObjectMappingFor(domainObjectType);
		}
		
		/**
		 * may return null
		 * @param attribName
		 * @param domainObjectType
		 * @return
		 */
		public FieldMapping getFieldMapping(String attribName, Class<?> domainObjectType) {
			return getObjectMappingFor(domainObjectType).getFieldMappingForField(attribName);
		}
		
		public List<FieldMapping> getBackwardFieldMappings(String attribName, Class<?> domainObjectType) {
			return ((IIntDomainAccess)domainAccess).getInternalDomainAccess()
					.getBackwardFieldMappings(attribName, domainObjectType);
		}
		
		public List<Class<?>> getCompoundTypesFor(Class<?> domainObjectType) {
			return ((IIntDomainAccess)domainAccess).getInternalDomainAccess()
					.getCompoundTypesFor(domainObjectType);
		}
		
		public String getLabelForClass(Class<?> clazz) {
			return ((IIntDomainAccess)domainAccess).getInternalDomainAccess()
					.getLabelForClass(clazz);
		}
		
		public Class<?> getClassForLabel(String label) {
			return ((IIntDomainAccess)domainAccess).getInternalDomainAccess()
					.getClassForLabel(label);
		}
		
		public InternalDomainAccess getInternalDomainAccess() {
			return ((IIntDomainAccess)domainAccess).getInternalDomainAccess();
		}
	}
}
