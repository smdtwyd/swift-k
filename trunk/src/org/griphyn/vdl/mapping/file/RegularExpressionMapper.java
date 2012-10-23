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


package org.griphyn.vdl.mapping.file;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.griphyn.vdl.mapping.AbsFile;
import org.griphyn.vdl.mapping.AbstractMapper;
import org.griphyn.vdl.mapping.DSHandle;
import org.griphyn.vdl.mapping.MappingParam;
import org.griphyn.vdl.mapping.MappingParamSet;
import org.griphyn.vdl.mapping.Path;
import org.griphyn.vdl.mapping.PhysicalFormat;

public class RegularExpressionMapper extends AbstractMapper {
	public static final MappingParam PARAM_SOURCE = new MappingParam("source");
	public static final MappingParam PARAM_MATCH = new MappingParam("match");
	public static final MappingParam PARAM_TRANSFORM = new MappingParam("transform");

	public RegularExpressionMapper() {
	}

	public void setParams(MappingParamSet params) {
		super.setParams(params);
		if (!PARAM_MATCH.isPresent(this)) {
			throw new RuntimeException("Missing parameter match!");
		}
	}

	public Collection<Path> existing() {
		if (exists(Path.EMPTY_PATH))
			return Arrays.asList(new Path[] { Path.EMPTY_PATH });
		else {
			return Collections.emptyList();
		}
	}

	public PhysicalFormat map(Path path) {
	    if (PARAM_MATCH.getRawValue(this) instanceof DSHandle) {
	        DSHandle h = (DSHandle) PARAM_MATCH.getRawValue(this);
	        if (!h.getType().isPrimitive()) {
	            throw new IllegalArgumentException("Non-primitive value specified for " + 
	                PARAM_MATCH.getName() + "; maybe you meant @filename(" + h.getPathFromRoot() + ")?");
	        }
	    }
		String match = PARAM_MATCH.getStringValue(this);
		String source = PARAM_SOURCE.getStringValue(this);
		String transform = PARAM_TRANSFORM.getStringValue(this);
		Pattern p = Pattern.compile(match);
		Matcher m = p.matcher(source);
		if (!m.find()) {
			throw new RuntimeException("No match found! source='" + source + "' match = '" + match
					+ "'");
		}
		// find group number to replace
		Pattern p2 = Pattern.compile("(\\\\\\d)");
		Matcher m2 = p2.matcher(transform);
		StringBuffer sb = new StringBuffer();
		while (m2.find()) {
                        String group = m2.group(1);
			int g = Integer.parseInt(group.substring(1));
                        try { 
         			m2.appendReplacement(sb, m.group(g));
                        }
                        catch (IndexOutOfBoundsException e)
                        {
                                throw new RuntimeException("regexp_mapper error: No group: \\\\" + g);
                        }
		}
		m2.appendTail(sb);
		return new AbsFile(sb.toString());
	}

	public boolean isStatic() {
		return true;
	}

	public static void main(String[] args) {
		RegularExpressionMapper reMapper = new RegularExpressionMapper();
		Map<String, Object> params = new HashMap<String, Object>();
		params.put("source", "2mass-j1223.fits");
		params.put("match", "(.*)\\.(.*)");
		params.put("transform", "\\1_area.\\2");
		MappingParamSet mps = new MappingParamSet();
		mps.setAll(params);
		reMapper.setParams(mps);
		System.out.println(reMapper.map(Path.EMPTY_PATH));
	}
}