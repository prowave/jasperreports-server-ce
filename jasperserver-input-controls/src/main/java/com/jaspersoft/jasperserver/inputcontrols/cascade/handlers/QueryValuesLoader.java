/*
 * Copyright (C) 2005 - 2020 TIBCO Software Inc. All rights reserved.
 * http://www.jaspersoft.com.
 *
 * Unless you have purchased a commercial license agreement from Jaspersoft,
 * the following license terms apply:
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.jaspersoft.jasperserver.inputcontrols.cascade.handlers;

import com.jaspersoft.jasperserver.api.JSMissingDataSourceFieldsException;
import com.jaspersoft.jasperserver.api.common.domain.impl.ExecutionContextImpl;
import com.jaspersoft.jasperserver.api.engine.common.service.EngineService;
import com.jaspersoft.jasperserver.api.engine.common.service.ReportInputControlInformation;
import com.jaspersoft.jasperserver.api.engine.jasperreports.service.impl.EhcacheEngineService;
import com.jaspersoft.jasperserver.api.logging.audit.context.AuditContext;
import com.jaspersoft.jasperserver.api.logging.audit.domain.AuditEvent;
import com.jaspersoft.jasperserver.api.logging.audit.domain.AuditEventType;
import com.jaspersoft.jasperserver.api.metadata.common.domain.InputControl;
import com.jaspersoft.jasperserver.api.metadata.common.domain.ListOfValuesItem;
import com.jaspersoft.jasperserver.api.metadata.common.domain.Query;
import com.jaspersoft.jasperserver.api.metadata.common.domain.Resource;
import com.jaspersoft.jasperserver.api.metadata.common.domain.ResourceReference;
import com.jaspersoft.jasperserver.api.metadata.common.domain.client.ListOfValuesItemImpl;
import com.jaspersoft.jasperserver.api.metadata.jasperreports.domain.ReportDataSource;
import com.jaspersoft.jasperserver.inputcontrols.cascade.CachedRepositoryService;
import com.jaspersoft.jasperserver.inputcontrols.cascade.CascadeResourceNotFoundException;
import com.jaspersoft.jasperserver.inputcontrols.cascade.InputControlValidationException;
import com.jaspersoft.jasperserver.inputcontrols.cascade.handlers.converters.DataConverterService;
import com.jaspersoft.jasperserver.inputcontrols.cascade.CachedEngineService;
import com.jaspersoft.jasperserver.inputcontrols.cascade.token.FilterResolver;
import com.jaspersoft.jasperserver.inputcontrols.cascade.token.ParameterTypeLookup;
import org.apache.commons.collections.OrderedMap;
import org.apache.commons.collections.OrderedMapIterator;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.mutable.MutableInt;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;

import static com.jaspersoft.jasperserver.inputcontrols.cascade.handlers.InputControlHandler.NOTHING_SUBSTITUTION_LABEL;
import static com.jaspersoft.jasperserver.inputcontrols.cascade.handlers.InputControlHandler.NOTHING_SUBSTITUTION_VALUE;

/**
 * @author Yaroslav.Kovalchyk
 * @version $Id$
 */
@Service
public class QueryValuesLoader implements ValuesLoader {

    public static final String COLUMN_VALUE_SEPARATOR = " | ";

    private static final Log log = LogFactory.getLog(QueryValuesLoader.class);

    @javax.annotation.Resource
    protected FilterResolver filterResolver;
    @javax.annotation.Resource
    protected CachedRepositoryService cachedRepositoryService;
    @javax.annotation.Resource
    protected CachedEngineService cachedEngineService;
    @javax.annotation.Resource
    protected EngineService engineService;
    @javax.annotation.Resource
    private DataConverterService dataConverterService;
    @javax.annotation.Resource
    private AuditContext concreteAuditContext;
    @javax.annotation.Resource
    protected ParameterTypeLookup parameterTypeCompositeLookup;

    @Override
    public List<ListOfValuesItem> loadValues(InputControl inputControl, ResourceReference dataSource, Map<String, Object> parameters, Map<String, Class<?>> parameterTypes, ReportInputControlInformation info, boolean isSingleSelect) throws CascadeResourceNotFoundException {
        int limit;
        int offset;
        String criteria;
        int totalLimit;
        OrderedMap results = null;
        Map<String, String> errors = new HashMap<>();

        createInputControlsAuditEvent(inputControl.getURIString(), parameters);

        List<ListOfValuesItem> result = null;
        ResourceReference dataSourceForQuery = resolveDatasource(inputControl, dataSource);
        final Query query = cachedRepositoryService.getResource(Query.class, inputControl.getQuery());

        Map<String, Object> domainSchemaParameters = new HashMap<String, Object>();

        //TODO Extract this parameter extension to separate interface
        prepareDomainDataSource(dataSourceForQuery, domainSchemaParameters);

        Map<String, Object> executionParameters = filterAndFillMissingQueryParameters(query, parameters, domainSchemaParameters);
        Map<String, Class<?>> executionParameterTypes = filterParameterTypes(executionParameters.keySet(), parameterTypes);
        Map<String, Class<?>> missingParameterTypes = findMissingParameterTypes(dataSource, executionParameters, executionParameterTypes);

        executionParameterTypes.putAll(missingParameterTypes);

        if (parameters!=null&&parameters.containsKey(EhcacheEngineService.IC_REFRESH_KEY)) {
        	executionParameters.put(EhcacheEngineService.IC_REFRESH_KEY,"true");
        }
        if (parameters!=null&&parameters.containsKey(EhcacheEngineService.DIAGNOSTIC_REPORT_URI)) {
            executionParameters.put(EhcacheEngineService.DIAGNOSTIC_REPORT_URI, parameters.get(EhcacheEngineService.DIAGNOSTIC_REPORT_URI));
        }
        if (parameters!=null&&parameters.containsKey(EhcacheEngineService.DIAGNOSTIC_STATE)) {
            executionParameters.put(EhcacheEngineService.DIAGNOSTIC_STATE, parameters.get(EhcacheEngineService.DIAGNOSTIC_STATE));
        }

        /* Typed results are returned */
        results = getResultsOrderedMap(inputControl, dataSourceForQuery, executionParameters, executionParameterTypes, results);
        addNothingLabelToResults(results, isSingleSelect, inputControl);

        if(results != null) {
            limit = getLimit(inputControl, parameters, errors);
            offset = getOffset(inputControl, parameters, results.size(), errors);

            checkLimitOffsetRange(errors);

            totalLimit = getTotalLimit(limit, offset, results.size());

            criteria = getCriteria(inputControl, parameters);

            setTotalCount(inputControl, parameters, info, criteria, results);

            result = getListOfValuesItems(inputControl, info, criteria, limit, offset, totalLimit, results);
        }

        closeInputControlsAuditEvent();

        return result;
    }

    /**
     * Filter the results to get totalCount by criteria if any and  add total count to result parameters
     * @param inputControl
     * @param parameters
     * @param info
     * @param criteria
     * @param results
     * @throws CascadeResourceNotFoundException
     */
    public void setTotalCount(InputControl inputControl, Map<String, Object> parameters, ReportInputControlInformation info, String criteria, OrderedMap results) throws CascadeResourceNotFoundException {
        if(criteria != null) {
            int totalCount = getTotalCountByCriteria(inputControl, info, results, criteria);
            addTotalCountToParameters(parameters, totalCount);
        } else {
            addTotalCountToParameters(parameters, results.size());
        }
    }

    protected int getTotalCountByCriteria(InputControl inputControl, ReportInputControlInformation info, OrderedMap results, String criteria) throws CascadeResourceNotFoundException {
        int totalCount = 0;

        OrderedMapIterator it = results.orderedMapIterator();
        while (it.hasNext()) {
            Object valueColumn = it.next();
            Object[] visibleColumns = (Object[]) it.getValue();
            String label = extractLabelFromResults(inputControl, info, visibleColumns, new StringBuilder());

            if (StringUtils.containsIgnoreCase(label, criteria)) {
                totalCount++;
            }
        }
        return totalCount;
    }

    private OrderedMap getResultsOrderedMap(InputControl inputControl, ResourceReference dataSourceForQuery, Map<String, Object> executionParameters, Map<String, Class<?>> executionParameterTypes, OrderedMap results) throws CascadeResourceNotFoundException {
        try {
            results = cachedEngineService.executeQuery(
                    ExecutionContextImpl.getRuntimeExecutionContext(), inputControl.getQuery(),
                    inputControl.getQueryValueColumn(), inputControl.getQueryVisibleColumns(),
                    dataSourceForQuery, executionParameters, executionParameterTypes, inputControl.getName());
        } catch (JSMissingDataSourceFieldsException e) {
            log.debug(e.getMessage(), e);
            // This occurs when a field previously found in the domain is missing
            // we ignore here as we do not need these values, and an error is rendered on the report canvas
        } catch (IllegalArgumentException e) {
            log.debug(e.getMessage(), e);
            // This occurs when a field previously found in the domain is missing
            // we ignore here as we do not need these values, and an error is rendered on the report canvas
        }
        return results;
    }

    protected List<ListOfValuesItem> getListOfValuesItems(InputControl inputControl, ReportInputControlInformation info, String criteria, int limit, int offset, int totalLimit, OrderedMap results) throws CascadeResourceNotFoundException {
        int toIndex;
        List<ListOfValuesItem> result = null;
        OrderedMapIterator it = results.orderedMapIterator();
        result = createListOfValuesItems(criteria, totalLimit, results, result);
        while (it.hasNext()) {
            Object valueColumn = it.next();
            Object[] visibleColumns = (Object[]) it.getValue();

            String label = extractLabelFromResults(inputControl, info, visibleColumns, new StringBuilder());
            if(!NOTHING_SUBSTITUTION_VALUE.equals(valueColumn)) {

                /**
                 * If the limit-offset is provided, filter the results based on the totalLimit
                 * and filter by search criteria when provided and then add item to result.
                 */
                if (createAndAddItem(criteria, totalLimit, result, valueColumn, label)) {
                    break;
                }
            }
        }

        if(result == null) {
            return null;
        } else {
            //get the toIndex based on the constructed result list size
            toIndex = getTotalLimit(limit, offset, result.size());
            /** Validate to see if offset is more than the result size
             * before getting the sublist.
             */
            return result.subList(validateOffset(offset, result.size(), null), toIndex);
        }
    }

    private List<ListOfValuesItem> createListOfValuesItems(String criteria, int totalLimit, OrderedMap results, List<ListOfValuesItem> result) {
        if(result == null) {
            result = new ArrayList<>(results.size());
            if(results.containsKey(NOTHING_SUBSTITUTION_VALUE)) {
                createAndAddItem(criteria, totalLimit, result, NOTHING_SUBSTITUTION_VALUE, NOTHING_SUBSTITUTION_LABEL);
            }
        }
        return result;
    }

    private boolean createAndAddItem(String criteria, int totalLimit, List<ListOfValuesItem> result, Object valueColumn, String label) {
        ListOfValuesItem item = new ListOfValuesItemImpl();
        item.setLabel(label);
        valueColumn = formatValueColumn(valueColumn);
        item.setValue(valueColumn);

        /**
         * If the limit-offset is provided, filter the results based on the totalLimit
         * and filter by search criteria when provided and then add item to result.
         */
        if (!checkLimitAndAddItem(criteria, totalLimit, result, item)) {
            //when result size reached the limit then break;
            return true;
        }
        return false;
    }

    private void addNothingLabelToResults(OrderedMap results, boolean isSingleSelect, InputControl inputControl) {
        if (results != null) {
            if(isSingleSelect && !inputControl.isMandatory()) {
                results.put(NOTHING_SUBSTITUTION_VALUE, new Object[]{NOTHING_SUBSTITUTION_LABEL});
            }
        }
    }

    protected String extractLabelFromResults(InputControl inputControl, ReportInputControlInformation info, Object[] visibleColumns, StringBuilder label) throws CascadeResourceNotFoundException {
        for (int i = 0; i < visibleColumns.length; i++) {
            Object visibleColumn = visibleColumns[i];
            String visibleColumnName = inputControl.getQueryVisibleColumns()[i];
            boolean isVisibleColumnMatchesValueColumn = inputControl.getQueryValueColumn().equals(visibleColumnName);

            checkLabelLength(label);

            String formattedValue = formatValueToString(visibleColumn, isVisibleColumnMatchesValueColumn, inputControl, info);
            label.append(visibleColumn != null ? formattedValue : InputControlHandler.NULL_SUBSTITUTION_LABEL);
        }
        return label.toString();
    }

    private void checkLabelLength(StringBuilder label) {
        if (label.length() > 0) {
            label.append(COLUMN_VALUE_SEPARATOR);
        }
    }

    private Object formatValueColumn(Object valueColumn) {
        if(valueColumn instanceof BigDecimal) {
            valueColumn = ((BigDecimal) valueColumn).stripTrailingZeros();
        }
        return valueColumn;
    }

    private String formatValueToString(Object visibleColumn, boolean isVisibleColumnMatchesValueColumn,
       InputControl inputControl, ReportInputControlInformation info)
            throws CascadeResourceNotFoundException {

        if (isVisibleColumnMatchesValueColumn) {
            return dataConverterService.formatSingleValue(visibleColumn, inputControl, info);
        } else {
            return dataConverterService.formatSingleValue(visibleColumn, (InputControl) null, null);
        }
    }

    /**
     * DomainFilterResolver needs access to the domain schema, which it can get from
     * the param map. FilterCore doesn't need this, and it would allocate a connection
     * that's not needed.
     */
    protected void prepareDomainDataSource(ResourceReference dataSourceRef, Map<String, Object> parameters) throws CascadeResourceNotFoundException {
    	ReportDataSource dataSource = (ReportDataSource) cachedRepositoryService.getResource(Resource.class, dataSourceRef);
        if (filterResolver.paramTestNeedsDataSourceInit(dataSource)) {
            parameters.putAll(cachedEngineService.getSLParameters(dataSource));
        }
    }

    /**
     * Create new Map with specified parameters, add only those which are used in query, find missing parameters and assign value Null to them.
     * This is done because we indicate Nothing selection in single select control as absence of parameter in map,
     * but QueryManipulator sets query to empty string and Executor throws exception if parameter is absent,
     * so we set Null as most suitable value as yet.
     *
     * @param query Query
     * @param parameters Map&lt;String, Object&gt;
     * @return Map&lt;String, Object&gt; copy of map, where missing parameters filled with Null values.
     */
    protected Map<String, Object> filterAndFillMissingQueryParameters(Query query, Map<String, Object> parameters, Map<String, Object> domainSchemaParameters) {
        HashMap<String, Object> parametersWithSchema = new HashMap<String, Object>();
        parametersWithSchema.putAll(parameters);
        parametersWithSchema.putAll(domainSchemaParameters);
        Set<String> queryParameterNames = filterResolver.getParameterNames(query.getSql(), parametersWithSchema);
        HashMap<String, Object> resolvedParameters = new HashMap<String, Object>();
        for (String queryParameterName : queryParameterNames) {
            // If parameter is missing, set Null.
            resolvedParameters.put(queryParameterName, parameters.get(queryParameterName));
        }
        resolvedParameters.putAll(domainSchemaParameters);
        return resolvedParameters;
    }

    /**
     * Filter only specified parameter types
     * @param parameters Parameter names
     * @param parameterTypes Map of parameter types
     * @return Filtered map of parameter types
     */
    protected Map<String, Class<?>> filterParameterTypes(Set<String> parameters, Map<String, Class<?>> parameterTypes) {
        Map<String, Class<?>> filteredParameterTypes = new HashMap<String, Class<?>>(parameters.size());
        for (String parameterName : parameters) {
            if (parameterTypes.containsKey(parameterName)) {
                filteredParameterTypes.put(parameterName, parameterTypes.get(parameterName));
            }
        }
        return filteredParameterTypes;
    }

    /**
     * Retrieves additional parameter types from dataSource (if it has any)
     *
     * @param dataSource a resource that might have parameters
     * @param parameters a map of all parameters
     * @param parameterTypes types that were find out earlier
     * @return a map with parameter name and type
     * @throws CascadeResourceNotFoundException
     */
    private Map<String, Class<?>> findMissingParameterTypes(ResourceReference dataSource,
                                                            Map<String, Object> parameters,
                                                            Map<String, Class<?>> parameterTypes) throws CascadeResourceNotFoundException {
        Set<String> missingParameterTypes = new HashSet<>(parameters.keySet());
        missingParameterTypes.removeAll(parameterTypes.keySet());
        if (!missingParameterTypes.isEmpty()) {
            return parameterTypeCompositeLookup.getParameterTypes(ExecutionContextImpl.getRuntimeExecutionContext(), dataSource, missingParameterTypes);
        } else {
            return Collections.emptyMap();
        }
    }

    @Override
    public Set<String> getMasterDependencies(InputControl inputControl, ResourceReference dataSource) throws CascadeResourceNotFoundException {
        Map<String, Object> filterParameters = new HashMap<String, Object>();
        ResourceReference dataSourceForQuery = resolveDatasource(inputControl, dataSource);
        prepareDomainDataSource(dataSourceForQuery, filterParameters);
        Query query = cachedRepositoryService.getResource(Query.class, inputControl.getQuery());
        String querySQL = query.getSql();
        return filterResolver.getParameterNames(querySQL, filterParameters);
    }

    protected ResourceReference resolveDatasource(InputControl inputControl, ResourceReference reportDatasource) throws CascadeResourceNotFoundException {
        ResourceReference queryReference = inputControl.getQuery();
        ResourceReference resolvedDatasource = reportDatasource;
        if (queryReference != null) {
            Resource queryResource = cachedRepositoryService.getResource(Resource.class, queryReference);
            if (queryResource instanceof Query && ((Query) queryResource).getDataSource() != null) {
                resolvedDatasource = ((Query) queryResource).getDataSource();
            }
        }
        return resolvedDatasource;
    }

    protected void createInputControlsAuditEvent(final String resourceUri, final Map<String, Object> parameters) {
        concreteAuditContext.doInAuditContext(new AuditContext.AuditContextCallback() {
            public void execute() {
                AuditEvent event = concreteAuditContext.createAuditEvent(AuditEventType.INPUT_CONTROLS_QUERY.toString());
                if (event.getResourceUri() == null) {
                    event.setResourceUri(resourceUri);
                }

                for (Map.Entry<String, Object> entry: parameters.entrySet()) {
                    if (entry.getKey() != null) {
                        concreteAuditContext.addPropertyToAuditEvent("inputControlParam", entry, event);
                    }
                }

            }
        });
    }

    protected void closeInputControlsAuditEvent() {
        concreteAuditContext.doInAuditContext(AuditEventType.INPUT_CONTROLS_QUERY.toString(), new AuditContext.AuditContextCallbackWithEvent() {
            public void execute(AuditEvent auditEvent) {
                concreteAuditContext.closeAuditEvent(auditEvent);
            }
        });
    }
}
