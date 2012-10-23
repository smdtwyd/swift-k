/*
 * Copyright 2012 University of Chicago
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


/*
 * Created on Dec 26, 2006
 */
package org.griphyn.vdl.karajan.lib;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.globus.cog.karajan.arguments.Arg;
import org.globus.cog.karajan.stack.VariableStack;
import org.globus.cog.karajan.util.TypeUtil;
import org.globus.cog.karajan.workflow.ExecutionException;
import org.griphyn.vdl.mapping.AbstractDataNode;
import org.griphyn.vdl.mapping.DSHandle;
import org.griphyn.vdl.mapping.ExternalDataNode;
import org.griphyn.vdl.mapping.MappingParam;
import org.griphyn.vdl.mapping.MappingParamSet;
import org.griphyn.vdl.mapping.Path;
import org.griphyn.vdl.mapping.RootArrayDataNode;
import org.griphyn.vdl.mapping.RootDataNode;
import org.griphyn.vdl.mapping.file.ConcurrentMapper;
import org.griphyn.vdl.type.Type;
import org.griphyn.vdl.type.Types;

public class New extends VDLFunction {

	public static final Logger logger = Logger.getLogger(New.class);

	public static final Arg OA_TYPE = new Arg.Optional("type", null);
	public static final Arg OA_MAPPING = new Arg.Optional("mapping", null);
	public static final Arg OA_VALUE = new Arg.Optional("value", null);
	public static final Arg OA_DBGNAME = new Arg.Optional("dbgname", null);
	public static final Arg OA_WAITFOR = new Arg.Optional("waitfor", null);

	static {
		setArguments(New.class,
				new Arg[] { OA_TYPE, OA_MAPPING, OA_VALUE, OA_DBGNAME, OA_WAITFOR});
	}

	public Object function(VariableStack stack) throws ExecutionException {
		String typename = TypeUtil.toString(OA_TYPE.getValue(stack));
		Object value = OA_VALUE.getValue(stack);
		@SuppressWarnings("unchecked")
        Map<String,Object> mapping = 
		    (Map<String,Object>) OA_MAPPING.getValue(stack);
		String dbgname = TypeUtil.toString(OA_DBGNAME.getValue(stack));
		String waitfor = (String) OA_WAITFOR.getValue(stack);
		String line = (String) getProperty("_defline");
		
		MappingParamSet mps = new MappingParamSet();
		mps.setAll(mapping);

		if (dbgname != null) {
			mps.set(MappingParam.SWIFT_DBGNAME, dbgname);
		}
		
		if (line != null) {
		    mps.set(MappingParam.SWIFT_LINE, line);
		}
		
		String threadPrefix = getThreadPrefix(stack);

		mps.set(MappingParam.SWIFT_RESTARTID, threadPrefix + ":" + dbgname);

		if (waitfor != null) {
			mps.set(MappingParam.SWIFT_WAITFOR, waitfor);
		}

		if (typename == null && value == null) {
			throw new ExecutionException("You must specify either a type or a value");
		}
	
		String mapper = (String) mps.get(MappingParam.SWIFT_DESCRIPTOR);
		if ("concurrent_mapper".equals(mapper)) {
		    mps.set(ConcurrentMapper.PARAM_THREAD_PREFIX, threadPrefix);
		}
		mps.set(MappingParam.SWIFT_BASEDIR, stack.getExecutionContext().getBasedir());
		
		try {
			Type type;
			if (typename == null) {
				throw new ExecutionException("vdl:new requires a type specification for value: " + value);
			}
			else {
				type = Types.getType(typename);
			}
			DSHandle handle;
			if (typename.equals("external")) {
				handle = new ExternalDataNode();
			}
			else if (type.isArray()) {
				// dealing with array variable
				handle = new RootArrayDataNode(type);
				if (value != null) {
					if (value instanceof RootArrayDataNode) {
						handle = (RootArrayDataNode) value;
					}
					else {
						if (!(value instanceof List)) {
							throw new ExecutionException("An array variable can only be initialized with a list of values");
						}
						int index = 0;
						Iterator<?> i = ((List<?>) value).iterator();
						while (i.hasNext()) {
							// TODO check type consistency of elements with
							// the type of the array
							Object n = i.next();
							Path p = Path.EMPTY_PATH.addLast(index, true);
							if (n instanceof DSHandle) {
								handle.getField(p).set((DSHandle) n);
							}
							else {
								throw new RuntimeException(
										"An array variable can only be initialized by a list of DSHandle values");
							}
							index++;
						}
					}
					handle.closeShallow();
				}

				handle.init(mps);
			}
			else if (value instanceof DSHandle) {
				handle = (DSHandle) value;
			}
			else {
				handle = new RootDataNode(type);
				handle.init(mps);
				if (value != null) {
					handle.setValue(internalValue(type, value));
				}
			}
			
			if (AbstractDataNode.provenance && logger.isDebugEnabled()) {
			    logger.debug("NEW id=" + handle.getIdentifier());
			}
			return handle;
		}
		catch (Exception e) {
			throw new ExecutionException(e);
		}
	}
}