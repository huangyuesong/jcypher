/************************************************************************
 * Copyright (c) 2014 IoT-Solutions e.U.
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

package iot.jcypher.domainquery.api;

import java.util.List;

import iot.jcypher.domainquery.ast.PredicateExpression;
import iot.jcypher.domainquery.internal.QueryExecutor.MappingInfo;
import iot.jcypher.query.values.JcNode;

public class APIAccess {

	public static BooleanOperation createBooleanOperation(PredicateExpression pe) {
		return new BooleanOperation(pe);
	}
	
	public static <T> DomainObjectMatch<T> createDomainObjectMatch(Class<T> domainObjectType,
			int num, MappingInfo mappingInfo) {
		return new DomainObjectMatch<T>(domainObjectType, num, mappingInfo);
	}
	
	public static List<JcNode> getNodes(DomainObjectMatch<?> dom) {
		return dom.getNodes();
	}
	
	public static List<Class<?>> getTypeList(DomainObjectMatch<?> dom) {
		return dom.getTypeList();
	}
	
	public static MappingInfo getMappingInfo(DomainObjectMatch<?> dom) {
		return dom.getMappingInfo();
	}
	
	public static String getBaseNodeName(DomainObjectMatch<?> dom) {
		return dom.getBaseNodeName();
	}
	
	public static <T> Class<T> getDomainObjectType(DomainObjectMatch<T> dom) {
		return dom.getDomainObjectType();
	}
}
