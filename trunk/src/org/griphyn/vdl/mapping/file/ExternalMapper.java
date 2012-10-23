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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.griphyn.vdl.mapping.AbsFile;
import org.griphyn.vdl.mapping.AbstractMapper;
import org.griphyn.vdl.mapping.MappingParam;
import org.griphyn.vdl.mapping.MappingParamSet;
import org.griphyn.vdl.mapping.Path;
import org.griphyn.vdl.mapping.PhysicalFormat;

public class ExternalMapper extends AbstractMapper {
	public static final Logger logger = Logger.getLogger(ExternalMapper.class);

	private Map<Path, AbsFile> map;
	private Map<String, Path> rmap;

	public static final MappingParam PARAM_EXEC = new MappingParam("exec");

	private static final String[] STRING_ARRAY = new String[0];

	public void setParams(MappingParamSet params) {
		super.setParams(params);
		map = new HashMap<Path, AbsFile>();
		rmap = new HashMap<String, Path>();
		String exec = PARAM_EXEC.getStringValue(this);
		String bdir = MappingParam.SWIFT_BASEDIR.getStringValue(this);
		if (bdir != null && !exec.startsWith("/")) {
			exec = bdir + File.separator + exec;
		}
		List<String> cmd = new ArrayList<String>();
		cmd.add(exec);
		for (String name : params.names()) {
			if (!name.contains("#") && !name.equals("exec")) {
				MappingParam tp = new MappingParam(name);
				cmd.add('-' + name);
				cmd.add(tp.getStringValue(this));
			}
		}
		try {
		    if (logger.isDebugEnabled()) {
		        logger.debug("invoking external mapper for " + getParam(MappingParam.SWIFT_DBGNAME) + ": " + cmd);
		    }
			Process p = Runtime.getRuntime().exec(cmd.toArray(STRING_ARRAY));
			List<String> lines = fetchOutput(p.getInputStream());
			if (logger.isDebugEnabled()) {
			    logger.debug("external mapper for " + getParam(MappingParam.SWIFT_DBGNAME) + " output: " + lines);
			}
			int ec = p.waitFor();
			if (ec != 0) {
				throw new RuntimeException("External executable failed. Exit code: " + ec + "\n\t"
						+ join(lines) + "\n\t" + join(fetchOutput(p.getErrorStream())));
			}
			processLines(lines);
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
		catch (InterruptedException e) {
			throw new RuntimeException(e);
		}
	}

	private String join(List<?> l) {
		StringBuffer sb = new StringBuffer();
		for (Object o : l) {
			sb.append(o);
			sb.append('\n');
			sb.append('\t');
		}
		return sb.toString();
	}

	private List<String> fetchOutput(InputStream is) throws IOException {
		ArrayList<String> lines = new ArrayList<String>();
		BufferedReader br = new BufferedReader(new InputStreamReader(is));
		String line = br.readLine();
		while (line != null) {
			lines.add(line);
			line = br.readLine();
		}
		return lines;
	}

	private void processLines(List<String> lines) {
		for (String line : lines) {
			int s = line.indexOf(' ');
			int t = line.indexOf('\t');
			int m = Math.min(s == -1 ? t : s, t == -1 ? s : t);
			if (m == -1) {
				throw new RuntimeException("Invalid line in mapper script output: " + line);
			}
			String spath = line.substring(0, m);
			Path p = Path.parse(spath);
			AbsFile f = new AbsFile(line.substring(m + 1).trim());
			map.put(p, f);
			rmap.put(spath, p);
		}
	}

	public Collection<Path> existing() {
		return map.keySet();
	}

	public Path rmap(String name) {
		if (name == null || name.equals("")) {
			return null;
		}
		return rmap.get(name);
	}

	public PhysicalFormat map(Path path) {
		return map.get(path);
	}

	public boolean isStatic() {
		return true;
	}
}