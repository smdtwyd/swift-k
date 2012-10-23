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
 * Created on Jul 18, 2010
 */
package org.griphyn.vdl.karajan.lib;

import java.util.List;

import org.globus.cog.karajan.arguments.Arg;
import org.globus.cog.karajan.arguments.VariableArguments;
import org.globus.cog.karajan.stack.VariableStack;
import org.globus.cog.karajan.util.TypeUtil;
import org.globus.cog.karajan.workflow.ExecutionException;

public class Flatten extends VDLFunction {
    
    static {
        setArguments(Flatten.class, new Arg[] { Arg.VARGS });
    }

    @Override
    protected Object function(VariableStack stack) throws ExecutionException {
        VariableArguments v = Arg.VARGS.get(stack);
        if (v.isEmpty()) {
            return "";
        }
        else {
            StringBuilder sb = new StringBuilder();
            flatten(sb, v.getAll());
            sb.deleteCharAt(sb.length() - 1);
            return sb.toString();
        }
    }

    private void flatten(StringBuilder sb, List l) {
        for (Object o : l) {
            if (o instanceof List) {
                flatten(sb, (List) o);
            }
            else {
                sb.append(TypeUtil.toString(o));
                sb.append('|');
            }
        }
    }
}