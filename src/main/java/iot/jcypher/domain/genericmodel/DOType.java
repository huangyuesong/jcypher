/************************************************************************
 * Copyright (c) 2015 IoT-Solutions e.U.
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

package iot.jcypher.domain.genericmodel;

import java.util.ArrayList;
import java.util.List;

public class DOType {

	private long nodeId;
	
	private String name;
	private DOType superType;
	private List<DOField> fields;

	public DOType(String name) {
		super();
		this.name = name;
		this.fields = new ArrayList<DOField>();
		this.nodeId = -1;
	}

	public String getName() {
		return name;
	}

	public List<DOField> getFields() {
		return fields;
	}

	public DOType getSuperType() {
		return superType;
	}

	public void setSuperType(DOType superType) {
		this.superType = superType;
	}

	public long getNodeId() {
		return nodeId;
	}

	public void setNodeId(long nodeId) {
		this.nodeId = nodeId;
	}
	
}
