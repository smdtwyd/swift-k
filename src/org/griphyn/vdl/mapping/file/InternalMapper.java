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
 * Created on Jun 7, 2015
 */
package org.griphyn.vdl.mapping.file;

import org.griphyn.vdl.mapping.Path;
import org.griphyn.vdl.mapping.PhysicalFormat;

public class InternalMapper extends ConcurrentMapper {
    @Override
    public void clean(Path path) {
        PhysicalFormat pf = map(path);
        logger.info("Cleaning file " + pf);
        FileGarbageCollector.getDefault().decreaseUsageCount(pf);
    }

    @Override
    public boolean isPersistent(Path path) {
        // if the path has been remapped to a persistent file, then
        // that actual file would already be marked as persistent in the
        // garbage collector
        return false;
    }
}
