/*
 * Swift Parallel Scripting Language (http://swift-lang.org)
 * Code from Java CoG Kit Project (see notice below) with modifications.
 *
 * Copyright 2005-2014 University of Chicago
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
 */

//----------------------------------------------------------------------
//This code is developed as part of the Java CoG Kit project
//The terms of the license can be found at http://www.cogkit.org/license
//This message may not be removed or altered.
//----------------------------------------------------------------------

/*
 * Created on Dec 16, 2009
 */
package org.globus.cog.abstraction.coaster.service.job.manager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.log4j.Logger;
import org.globus.cog.coaster.channels.ChannelListener;
import org.globus.cog.coaster.channels.CoasterChannel;
import org.globus.cog.coaster.commands.Command;
import org.globus.cog.coaster.commands.Command.Callback;
import org.globus.cog.coaster.commands.ShutdownCommand;

public class Node implements Callback, ChannelListener {
    public static final Logger logger = Logger.getLogger(Node.class);

    private final int id, concurrency;
    private final List<Cpu> cpus;
    private Block block;
    private final CoasterChannel channel;
    private boolean shutdown;
    private final String hostname;

    public Node(int id, Block block, String workerHostname,
                CoasterChannel channel, int concurrency) {
        this.id = id;
        this.block = block;
        this.hostname = workerHostname;
        this.channel = channel;
        channel.addListener(this);
        this.concurrency = concurrency;
        Settings settings = block.getAllocationProcessor().getSettings();
        cpus = new ArrayList<Cpu>(settings.getJobsPerNode());

        if (logger.isDebugEnabled()) {
            logger.debug("new: " + this);
        }
    }

    @Deprecated
    public Node(int id, Block block, CoasterChannel channel) {
        this.id = id;
        this.block = block;
        this.hostname = null;
        this.concurrency = 1;
        this.channel = channel;
        Settings settings = block.getAllocationProcessor().getSettings();
        cpus = new ArrayList<Cpu>(settings.getJobsPerNode());
    }

    public void add(Cpu cpu) {
        cpus.add(cpu);
    }

    public Block getBlock() {
        return block;
    }

    public void shutdown() {
        logger.info("Shutdown " + this);
        synchronized(this) {
            if (shutdown) {
                return;
            }
            else {
                shutdown = true;
            }
        }
        try {
            CoasterChannel channel = getChannel();
            channel.setLocalShutdown();
            ShutdownCommand cmd = new ShutdownCommand();
            cmd.executeAsync(channel, this);
        }
        catch (Exception e) {
            logger.info("Failed to shut down worker", e);
            block.forceShutdown();
        }
    }

    public void errorReceived(Command cmd, String msg, Exception t) {
        logger.info("Failed to shut down " + this + ": " + msg, t);
        block.forceShutdown();
    }

    public void replyReceived(Command cmd) {
        logger.info(this + " shut down successfully");
       	getChannel().close();
        channel.close();
        block.getAllocationProcessor().getRLogger().log("WORKER_SHUTDOWN blockid=" + block.getId() + " id=" + Block.IDF.format(id));
    }

    public String getHostname() {
        return hostname;
    }

    @Override
    public String toString() {
        return "Node [" + hostname + "] " + id;
    }

    public Collection<Cpu> getCpus() {
        return cpus;
    }

    public synchronized CoasterChannel getChannel() {
        return channel;
    }
    
    public void channelClosed(CoasterChannel channel, Exception e) {
        if (logger.isInfoEnabled()) {
            logger.info(this + " lost connection to worker; removing node from block.", e);
        }
        for (Cpu cpu : cpus) {
            cpu.taskFailed("Connection to worker lost", e);
        }
        Settings settings = block.getAllocationProcessor().getSettings();
        Block block = getBlock();
        block.removeNode(this);
        Block.totalActiveWorkers -= settings.getJobsPerNode();
        Block.totalFailedWorkers += settings.getJobsPerNode();
        block.getAllocationProcessor().getRLogger().log("WORKER_LOST blockid=" + block.getId() + " id=" + Block.IDF.format(id));
    }

    public int getId() {
        return id;
    }

    public int getConcurrency() {
        return concurrency;
    }
}
