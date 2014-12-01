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

package iot.jcypher.domainquery.ast;

import iot.jcypher.domainquery.api.IPredicateOperand1;
import iot.jcypher.query.values.ValueElement;

public class PredicateExpression implements IASTObject {

	private IPredicateOperand1 value_1;
	private Operator operator;
	private Object value_2;
	private int negationCount;

	public PredicateExpression(IPredicateOperand1 value_1) {
		super();
		this.value_1 = value_1;
		this.negationCount = 0;
	}
	
	public Operator getOperator() {
		return operator;
	}

	public void setOperator(Operator operator) {
		this.operator = operator;
	}
	
	public IPredicateOperand1 getValue_1() {
		return value_1;
	}

	public Object getValue_2() {
		return value_2;
	}

	public void setValue_2(Object value_2) {
		this.value_2 = value_2;
	}

	public void addNegation() {
		this.negationCount++;
	}

	public int getNegationCount() {
		return negationCount;
	}

	/*****************************************************************/
	public enum Operator {
		EQUALS, NOT, LT, GT, LTE, GTE, LIKE, IN, IS_NULL
	}
}